package io.github.anton415.centralbank.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.read.ListAppender;
import io.github.anton415.centralbank.application.config.AppConfig;
import io.github.anton415.centralbank.application.config.AppConfigLoadException;
import io.github.anton415.centralbank.application.config.AppConfigLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/**
 * Проверяет события диагностики и их текстовое представление без перехвата системной консоли.
 *
 * <p>Изолированный контекст принадлежит одному тесту; глобальная конфигурация только читается.
 */
@DisplayName("Стартовая диагностика")
class StartupDiagnosticsTest {

  private LoggerContext context;
  private Logger logger;
  private ListAppender<ILoggingEvent> appender;
  private StartupDiagnostics diagnostics;

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() {
    context = new LoggerContext();
    logger = context.getLogger("startup-test");
    logger.setLevel(Level.INFO);
    logger.setAdditive(false);

    appender = new ListAppender<>();
    appender.setContext(context);
    appender.start();
    logger.addAppender(appender);

    diagnostics = new StartupDiagnostics(logger);
  }

  @AfterEach
  void tearDown() {
    context.stop();
  }

  @Test
  @DisplayName("Сообщает об успешной загрузке на уровне INFO")
  void reportsConfigurationLoaded() {
    diagnostics.configurationLoaded(new AppConfig("Sandbox", "127.0.0.1", 8080));

    assertEquals(1, appender.list.size(),
        "Успешная загрузка должна создавать ровно одно событие");
    var event = appender.list.getFirst();

    assertAll(
        "Событие должно описывать загрузку настроек, включая только разрешённые поля",
        () -> assertEquals(Level.INFO, event.getLevel(),
            "Успешная загрузка должна иметь уровень INFO"),
        () -> assertEquals("event=configuration_loaded port=8080 java=25",
            event.getFormattedMessage(), "Сообщение должно содержать порт и версию Java"),
        () -> assertNull(event.getThrowableProxy(),
            "Успешное событие не должно содержать исключение"));
  }

  @Test
  @DisplayName("Не выводит название и адрес из конфигурации")
  void omitsUnapprovedConfigurationFields() {
    String nameMarker = "private-name-marker";
    String hostMarker = "private-host-marker";

    diagnostics.configurationLoaded(new AppConfig(nameMarker, hostMarker, 8080));

    assertEquals(1, appender.list.size(), "Проверяется текст реально созданного события");
    String output = render(appender.list.getFirst());

    assertAll(
        "В журнал должны попадать только явно разрешённые поля",
        () -> assertFalse(output.contains(nameMarker),
            "Название не должно попадать в журнал через вывод всей конфигурации"),
        () -> assertFalse(output.contains(hostMarker),
            "Адрес не должен попадать в журнал через вывод всей конфигурации"));
  }

  @Test
  @DisplayName("Сообщает об ошибке на уровне ERROR без цепочки исключений")
  void reportsFailureWithoutThrowable() {
    var exception = new AppConfigLoadException("server.port must be an integer");
    // Чужие вложенные исключения не должны попасть в лог вместе с безопасной причиной.
    exception.initCause(new IllegalArgumentException("private-cause-marker"));
    exception.addSuppressed(new IllegalStateException("private-suppressed-marker"));

    diagnostics.configurationFailed(exception);

    assertEquals(1, appender.list.size(), "Ошибка должна создавать ровно одно событие");
    var event = appender.list.getFirst();
    String output = render(event);

    assertAll(
        "Диагностика должна содержать причину без самого объекта исключения",
        () -> assertEquals(Level.ERROR, event.getLevel(),
            "Ошибка обязательной конфигурации должна иметь уровень ERROR"),
        () -> assertEquals(
            "event=configuration_load_failed reason=server.port must be an integer",
            event.getFormattedMessage(), "Ошибка должна сохранять понятную причину"),
        () -> assertNull(event.getThrowableProxy(),
            "В событие не должен передаваться Throwable"),
        () -> assertFalse(output.contains("private-cause-marker"),
            "Причина вложенного исключения не должна раскрываться в журнале"),
        () -> assertFalse(output.contains("private-suppressed-marker"),
            "Подавленные исключения не должны раскрываться в журнале"));
  }

  @Test
  @DisplayName("Безопасно журналирует ошибку настоящего INI-загрузчика")
  void logsLoaderFailureWithoutInvalidValue() throws Exception {
    String marker = "sensitive-test-marker";
    Path file = tempDir.resolve("application.ini");
    Files.writeString(file,
        """
        [application]
        name = Sandbox
        [server]
        host = 127.0.0.1
        port = %s
        """.formatted(marker), StandardCharsets.UTF_8);

    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(file),
        "Текстовый маркер вместо порта должен вызвать ошибку загрузки");
    diagnostics.configurationFailed(exception);

    assertEquals(1, appender.list.size(), "Ошибка загрузчика должна создавать одно событие");
    var event = appender.list.getFirst();
    String output = render(event);

    assertAll(
        "Связь загрузчика с диагностикой должна сохранять безопасное сообщение",
        () -> assertEquals(Level.ERROR, event.getLevel(), "Ошибка должна иметь уровень ERROR"),
        () -> assertTrue(output.contains("server.port must be an integer"),
            "Журнал должен объяснять проблему с портом"),
        () -> assertFalse(output.contains(marker),
            "Отвергнутое значение не должно попадать в итоговый текст журнала"),
        () -> assertNull(event.getThrowableProxy(), "Ошибка должна передаваться как текст"));
  }

  @DisplayName("Экранирует переводы строк в причине ошибки")
  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("lineBreaks")
  void escapesLineBreaks(String label, String separator, String escaped) {
    diagnostics.configurationFailed(new AppConfigLoadException("first" + separator + "second"));

    assertEquals(1, appender.list.size(), "Диагностика должна создавать одно событие");
    var event = appender.list.getFirst();
    String message = event.getFormattedMessage();

    assertAll(
        "Причина должна оставаться одной строкой",
        () -> assertEquals("event=configuration_load_failed reason=first" + escaped + "second",
            message, "Перевод строки должен заменяться видимой последовательностью"),
        () -> assertFalse(message.contains("\r"), "Тело сообщения не должно содержать CR"),
        () -> assertFalse(message.contains("\n"), "Тело сообщения не должно содержать LF"),
        () -> assertEquals(1L, render(event).lines().count(),
            "Encoder должен выводить одну строку, включая завершающий разделитель"));
  }

  @Test
  @DisplayName("При уровне WARN пропускает ERROR и подавляет INFO")
  void respectsConfiguredLevel() {
    logger.setLevel(Level.WARN);

    diagnostics.configurationLoaded(new AppConfig("Sandbox", "127.0.0.1", 8080));
    diagnostics.configurationFailed(new AppConfigLoadException("server.port is required"));

    assertEquals(1, appender.list.size(), "При WARN должен остаться только ERROR");
    assertEquals(Level.ERROR, appender.list.getFirst().getLevel(),
        "Ошибка запуска не должна теряться из-за отключения INFO");
  }

  @Test
  @DisplayName("Сообщает об ошибке даже при отсутствии её описания")
  void handlesMissingFailureMessage() {
    diagnostics.configurationFailed(new AppConfigLoadException(null));

    assertEquals(1, appender.list.size(), "Отсутствие текста ошибки не должно ломать диагностику");
    assertEquals("event=configuration_load_failed reason=Configuration loading failed",
        appender.list.getFirst().getFormattedMessage(),
        "При отсутствии описания должно использоваться понятное фиксированное сообщение");
  }

  @Test
  @DisplayName("Подключает Logback и применяет настройки logback.xml")
  void usesConfiguredLogbackProvider() {
    // Только читаем общий контекст: его изменение затронуло бы другие тесты и библиотеки.
    var configuredContext = assertInstanceOf(LoggerContext.class, LoggerFactory.getILoggerFactory(),
        "Реализацией SLF4J должен быть Logback");
    var root = configuredContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var console = assertInstanceOf(ConsoleAppender.class, root.getAppender("CONSOLE"),
        "Root logger должен использовать CONSOLE из основного logback.xml");
    var encoder = assertInstanceOf(PatternLayoutEncoder.class, console.getEncoder(),
        "Консоль должна использовать PatternLayoutEncoder");

    assertAll(
        "Основная конфигурация должна задавать рабочий вывод в UTF-8",
        () -> assertEquals(Level.INFO, root.getLevel(), "Базовый уровень должен быть INFO"),
        () -> assertEquals("System.err", console.getTarget(),
            "Диагностика должна выводиться в stderr"),
        () -> assertTrue(console.isStarted(), "Консольный appender должен быть запущен"),
        () -> assertTrue(encoder.isStarted(), "Encoder должен быть запущен"),
        () -> assertEquals(StandardCharsets.UTF_8, encoder.getCharset(),
            "Текст должен кодироваться в UTF-8"));
  }

  @Test
  @DisplayName("Сообщает об открытии порта на уровне INFO без исключения")
  void reportsApplicationStarted() {
    diagnostics.applicationStarted(49152);

    assertEquals(1, appender.list.size(), "Запуск должен создавать одно событие");
    var event = appender.list.getFirst();
    assertAll(
        () -> assertEquals(Level.INFO, event.getLevel(), "Успешный запуск имеет уровень INFO"),
        () -> assertEquals("event=application_started port=49152", event.getFormattedMessage(),
            "Диагностика должна использовать переданный фактический порт"),
        () -> assertNull(event.getThrowableProxy(), "Успешный запуск не содержит Throwable"));
  }

  @Test
  @DisplayName("Сообщает об ошибке аргументов фиксированным текстом на уровне ERROR")
  void reportsInvalidArguments() {
    diagnostics.argumentsInvalid();

    assertEquals(1, appender.list.size(), "Ошибка должна создавать одно событие");
    var event = appender.list.getFirst();
    assertAll(
        () -> assertEquals(Level.ERROR, event.getLevel(), "Ошибка аргументов имеет уровень ERROR"),
        () -> assertEquals("event=arguments_invalid reason=expected_one_config_path",
            event.getFormattedMessage(), "Сообщение должно объяснять ожидаемый аргумент"),
        () -> assertNull(event.getThrowableProxy(), "Ошибка не должна содержать Throwable"));
  }

  @Test
  @DisplayName("Сообщает об ошибке runtime фиксированным текстом на уровне ERROR")
  void reportsRuntimeFailure() {
    diagnostics.runtimeFailed();

    assertEquals(1, appender.list.size(), "Ошибка должна создавать одно событие");
    var event = appender.list.getFirst();
    assertAll(
        () -> assertEquals(Level.ERROR, event.getLevel(), "Ошибка runtime имеет уровень ERROR"),
        () -> assertEquals("event=application_failed reason=server_lifecycle_failed",
            event.getFormattedMessage(), "Ошибка не должна раскрывать стороннее сообщение"),
        () -> assertNull(event.getThrowableProxy(), "Ошибка не должна содержать Throwable"));
  }

  /** Проверяет итоговый текст с включённым выводом исключений, если они прикреплены к событию. */
  private String render(ILoggingEvent event) {
    var encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setCharset(StandardCharsets.UTF_8);
    encoder.setPattern("%level %msg%n%ex");
    encoder.start();
    try {
      return new String(encoder.encode(event), StandardCharsets.UTF_8);
    } finally {
      encoder.stop();
    }
  }

  private static Stream<Arguments> lineBreaks() {
    return Stream.of(
        arguments("CR", "\r", "\\r"),
        arguments("LF", "\n", "\\n"),
        arguments("CRLF", "\r\n", "\\r\\n"));
  }
}
