package io.github.anton415.centralbank.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.anton415.centralbank.application.bootstrap.StartupDiagnostics;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Проверяет сборку приложения и коды результата без System.exit в тестовой JVM. */
@DisplayName("Точка сборки приложения")
@Timeout(15)
class ApplicationMainTest {

  @TempDir
  Path tempDir;

  private LoggerContext context;
  private ListAppender<ILoggingEvent> appender;
  private StartupDiagnostics diagnostics;
  private CountDownLatch startupSignal;

  @BeforeEach
  void setUp() {
    context = new LoggerContext();
    var logger = context.getLogger("main-test");
    logger.setLevel(Level.INFO);
    logger.setAdditive(false);
    startupSignal = new CountDownLatch(1);
    appender = new ListAppender<>() {
      @Override
      protected void append(ILoggingEvent event) {
        super.append(event);
        if (event.getFormattedMessage().startsWith("event=application_started ")) {
          startupSignal.countDown();
        }
      }
    };
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);
    diagnostics = new StartupDiagnostics(logger);
  }

  @AfterEach
  void tearDown() {
    context.stop();
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("invalidArguments")
  @DisplayName("Возвращает код 2 при неправильных аргументах без раскрытия их значений")
  void rejectsInvalidArguments(String label, String[] args) {
    int result = ApplicationMain.run(args, diagnostics);

    assertAll(
        () -> assertEquals(2, result, "Ошибка аргументов должна возвращать код 2"),
        () -> assertEquals(List.of("event=arguments_invalid reason=expected_one_config_path"),
            messages(), "При неправильных аргументах загрузка и запуск не выполняются"));
  }

  @Test
  @DisplayName("Отсутствующий файл возвращает код 2 и понятную ошибку загрузки")
  void rejectsMissingFile() {
    Path missing = tempDir.resolve("missing.ini");

    int result = ApplicationMain.run(new String[] {missing.toString()}, diagnostics);

    assertAll(
        () -> assertEquals(2, result, "Ошибка конфигурации должна возвращать код 2"),
        () -> assertEquals(List.of(
            "event=configuration_load_failed reason=Cannot read configuration file: " + missing),
            messages(), "При ошибке чтения сервер не должен запускаться"));
  }

  @ParameterizedTest(name = "Порт {0} отклоняется до запуска")
  @ValueSource(strings = {"private-port-marker", "0", "65536"})
  @DisplayName("Ошибки INI завершают запуск с кодом 2 и безопасной диагностикой")
  void rejectsInvalidConfiguration(String port) throws Exception {
    Path config = writeConfig(port);

    int result = ApplicationMain.run(new String[] {config.toString()}, diagnostics);

    assertEquals(2, result, "Некорректная конфигурация должна возвращать код 2");
    assertEquals(1, appender.list.size(), "Ошибка должна возникнуть до запуска сервера");
    var event = appender.list.getFirst();
    assertAll(
        () -> assertTrue(event.getFormattedMessage().startsWith("event=configuration_load_failed "),
            "Должна быть зарегистрирована ошибка конфигурации"),
        () -> assertFalse(event.getFormattedMessage().contains("private-port-marker"),
            "Исходное значение не должно попасть в диагностику"),
        () -> assertNull(event.getThrowableProxy(), "Цепочка исключений не должна попасть в лог"));
  }

  @Test
  @DisplayName("Занятый порт возвращает код 1 без ложного события успешного запуска")
  void reportsOccupiedPort() throws Exception {
    try (var occupied = new ServerSocket()) {
      occupied.bind(new InetSocketAddress("127.0.0.1", 0));
      int port = occupied.getLocalPort();
      Path config = writeConfig(Integer.toString(port));

      int result = ApplicationMain.run(new String[] {config.toString()}, diagnostics);

      assertAll(
          () -> assertEquals(1, result, "Ошибка запуска runtime должна возвращать код 1"),
          () -> assertEquals(List.of(
              "event=configuration_loaded port=" + port + " java=25",
              "event=application_failed reason=server_lifecycle_failed"), messages(),
              "Успешная загрузка INI ещё не означает успешный запуск сервера"),
          () -> assertTrue(appender.list.stream()
              .allMatch(event -> event.getThrowableProxy() == null),
              "Ошибка Jetty не должна передаваться в прикладную диагностику как Throwable"));
    }
  }

  @Test
  @DisplayName("Прерывание ожидания останавливает сервер и сохраняет флаг прерывания")
  void preservesInterruptionAndReleasesPort() throws Exception {
    int port;
    try (var probe = new ServerSocket()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      port = probe.getLocalPort();
    }
    // INI запрещает порт 0: для проверки полного запуска передаём выбранный конкретный порт.
    Path config = writeConfig(Integer.toString(port));
    var interrupted = new AtomicBoolean();
    var result = new FutureTask<Integer>(() -> {
      int code = ApplicationMain.run(new String[] {config.toString()}, diagnostics);
      interrupted.set(Thread.currentThread().isInterrupted());
      return code;
    });
    Thread runner = Thread.ofPlatform().start(result);
    try {
      assertTrue(startupSignal.await(5, TimeUnit.SECONDS),
          "Сервер должен сообщить об успешном запуске");
      runner.interrupt();
      assertEquals(1, result.get(5, TimeUnit.SECONDS), "Прерывание должно возвращать код 1");
      assertTrue(interrupted.get(), "Обработчик должен восстановить флаг прерывания");
      assertEquals(List.of(
          "event=configuration_loaded port=" + port + " java=25",
          "event=application_started port=" + port,
          "event=application_failed reason=server_lifecycle_failed"), messages(),
          "События должны отражать порядок загрузки, запуска и прерывания");
      try (var rebound = new ServerSocket()) {
        rebound.setReuseAddress(true);
        rebound.bind(new InetSocketAddress("127.0.0.1", port));
        assertEquals(port, rebound.getLocalPort(), "Прерывание должно освобождать порт");
      }
    } finally {
      runner.interrupt();
      runner.join(5000);
      assertFalse(runner.isAlive(), "Поток приложения не должен оставаться после теста");
    }
  }

  private Path writeConfig(String port) throws Exception {
    return Files.writeString(tempDir.resolve("application.ini"),
        """
        [application]
        name = private-name-marker
        [server]
        host = 127.0.0.1
        port = %s
        """.formatted(port));
  }

  private List<String> messages() {
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  private static Stream<Arguments> invalidArguments() {
    return Stream.of(
        arguments("Нет аргументов", new String[0]),
        arguments("Лишний аргумент", new String[] {"private-marker", "extra"}),
        arguments("Пустой путь", new String[] {""}),
        arguments("Пробельный путь", new String[] {"  "}),
        arguments("Недопустимый символ пути", new String[] {"private-marker\u0000.ini"}));
  }
}
