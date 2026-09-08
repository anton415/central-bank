package io.github.anton415.centralbank.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Проверяет System.exit и shutdown hook в отдельной JVM, не завершая JVM JUnit. */
@DisplayName("Запуск приложения отдельным процессом")
@Timeout(30)
class ApplicationProcessTest {

  @TempDir
  Path tempDir;

  @ParameterizedTest(name = "Ошибка процесса: {0}")
  @ValueSource(strings = {"arguments", "missing", "invalid"})
  @DisplayName("Ошибки входных данных завершают JVM кодом 2 без раскрытия значений")
  void exitsWithInputError(String scenario) throws Exception {
    Path config = tempDir.resolve("application.ini");
    if (scenario.equals("invalid")) {
      writeConfig(config, "private-port-marker");
    }
    var command = command();
    if (!scenario.equals("arguments")) {
      command.add(config.toString());
    }
    Path output = tempDir.resolve("output.log");
    Process process = new ProcessBuilder(command).redirectErrorStream(true)
        .redirectOutput(output.toFile()).start();
    try {
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Процесс должен завершиться после ошибки");
      String text = Files.readString(output);
      String event = scenario.equals("arguments")
          ? "event=arguments_invalid" : "event=configuration_load_failed";
      assertAll(
          () -> assertEquals(2, process.exitValue(),
              "main должен передать код 2 операционной системе"),
          () -> assertTrue(text.contains(event), "Ошибка должна выводиться через основной Logback"),
          () -> assertFalse(text.contains("event=application_started"),
              "Некорректная конфигурация не должна приводить к запуску"),
          () -> assertFalse(text.contains("private-port-marker"),
              "Некорректное значение не должно попасть в консоль"),
          () -> assertFalse(text.contains("private-name-marker"),
              "Консоль не должна содержать всю конфигурацию"));
    } finally {
      terminateIfAlive(process);
    }
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  @DisplayName("Процесс отвечает по HTTP и выполняет остановку Jetty при завершении JVM")
  void servesHealthAndStopsOnTermination() throws Exception {
    int port;
    try (var probe = new ServerSocket()) {
      probe.bind(new InetSocketAddress("127.0.0.1", 0));
      port = probe.getLocalPort();
    }
    Path config = tempDir.resolve("application.ini");
    writeConfig(config, Integer.toString(port));
    var command = command();
    command.add(config.toString());

    try (var readerExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      try {
        var started = new CompletableFuture<Void>();
        final var output = readerExecutor.submit(() -> {
          var text = new StringBuilder();
          // Продолжаем читать после старта: иначе pipe может заполниться и заблокировать процесс.
          try (var reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
              text.append(line).append('\n');
              if (line.contains("event=application_started port=" + port)) {
                started.complete(null);
              }
            }
          } finally {
            started.completeExceptionally(
                new IllegalStateException("Process ended before startup"));
          }
          return text.toString();
        });
        started.get(10, TimeUnit.SECONDS);

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
          var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
              .timeout(Duration.ofSeconds(5)).GET().build();
          var response = client.send(request, HttpResponse.BodyHandlers.ofString());
          assertAll(
              () -> assertEquals(200, response.statusCode(),
                  "Запущенный процесс должен отвечать HTTP 200"),
              () -> assertEquals("UP\n", response.body(),
                  "Должен работать настоящий HealthHandler"));
        }

        // Handle посылает SIGTERM, сохраняя pipe открытым для последних сообщений shutdown hook.
        assertTrue(process.toHandle().destroy(), "ОС должна принять запрос завершения процесса");
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "JVM должна завершиться после сигнала");
        String text = output.get(5, TimeUnit.SECONDS);
        assertAll(
            () -> assertTrue(text.contains("Stopped oejs.Server"),
                "Shutdown hook должен вызвать остановку Jetty перед завершением JVM: " + text),
            () -> assertFalse(text.contains("private-name-marker"),
                "Название из INI не должно попасть в консоль"));
        try (var rebound = new ServerSocket()) {
          rebound.setReuseAddress(true);
          rebound.bind(new InetSocketAddress("127.0.0.1", port));
          assertEquals(port, rebound.getLocalPort(), "После завершения порт должен быть свободен");
        }
      } finally {
        terminateIfAlive(process);
      }
    }
  }

  private static List<String> command() {
    // Surefire передаёт полный classpath; запасной вариант позволяет запускать тест из IDE.
    String classpath = System.getProperty("surefire.test.class.path",
        System.getProperty("java.class.path"));
    return new ArrayList<>(List.of(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-classpath", classpath, ApplicationMain.class.getName()));
  }

  private static void writeConfig(Path path, String port) throws Exception {
    Files.writeString(path,
        """
        [application]
        name = private-name-marker
        [server]
        host = 127.0.0.1
        port = %s
        """.formatted(port));
  }

  private static void terminateIfAlive(Process process) throws Exception {
    if (process.isAlive()) {
      process.destroyForcibly();
      assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Тест не должен оставлять дочерний процесс");
    }
  }
}
