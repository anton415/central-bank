package io.github.anton415.centralbank.application.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Проверяет HTTP-ответы и освобождение ресурсов на настоящих loopback-соединениях. */
@DisplayName("Жизненный цикл встроенного Jetty")
@Timeout(15)
class JettyRuntimeTest {

  private static final String HOST = "127.0.0.1";

  @Test
  @DisplayName("Сервер отвечает на /health и останавливается при закрытии")
  void shouldRespondToHealthAndStop() throws Exception {
    // Порт 0 исключает поиск свободного порта с гонкой между поиском и запуском.
    var runtime = new JettyRuntime(HOST, 0, new HealthHandler());

    try (runtime; var client = newClient()) {
      runtime.start();

      int port = runtime.localPort();
      assertTrue(port > 0, "После запуска сервер должен получить сетевой порт");
      var response = get(client, port, "/health");

      assertAll(
          () -> assertEquals(200, response.statusCode(),
              "Проверка доступности должна вернуть HTTP 200"),
          () -> assertEquals("UP\n", response.body(),
              "Ответ должен содержать ожидаемый статус"),
          () -> assertEquals("text/plain; charset=UTF-8",
              response.headers().firstValue("Content-Type").orElseThrow(),
              "Ответ должен объявлять текст в UTF-8"));
    }

    assertTrue(runtime.isStopped(), "После закрытия сервер должен быть остановлен");
  }

  @ParameterizedTest(name = "Путь {0} возвращает HTTP 404")
  @ValueSource(strings = {"/", "/missing", "/health/", "/healthcheck"})
  @DisplayName("Обработчик не принимает неизвестные пути и похожие префиксы")
  void shouldReturnNotFound(String path) throws Exception {
    try (var runtime = new JettyRuntime(HOST, 0, new HealthHandler());
        var client = newClient()) {
      runtime.start();

      assertEquals(404, get(client, runtime.localPort(), path).statusCode(),
          "Неизвестный путь должен обрабатываться как HTTP 404");
    }
  }

  @Test
  @DisplayName("Остановка освобождает порт для нового слушателя")
  void shouldReleasePortOnClose() throws Exception {
    int port;
    try (var runtime = new JettyRuntime(HOST, 0, new HealthHandler())) {
      runtime.start();
      port = runtime.localPort();
    }

    // Повторная привязка проверяет реальный сетевой ресурс, а не только флаг состояния.
    try (var socket = new ServerSocket()) {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress(HOST, port));
      assertEquals(port, socket.getLocalPort(), "Освобождённый порт должен быть доступен");
    }
  }

  @Test
  @DisplayName("Повторное закрытие запущенного сервера безопасно")
  void shouldAllowRepeatedClose() throws Exception {
    var runtime = new JettyRuntime(HOST, 0, new HealthHandler());
    try (runtime) {
      runtime.start();
      runtime.close();
      assertDoesNotThrow(runtime::close, "Повторное закрытие не должно выбрасывать ошибку");
      assertTrue(runtime.isStopped(), "Сервер должен оставаться остановленным");
    }
  }

  @Test
  @DisplayName("Создание runtime не открывает порт и допускает закрытие до запуска")
  void shouldNotStartInConstructor() throws Exception {
    try (var runtime = new JettyRuntime(HOST, 0, new HealthHandler())) {
      assertAll(
          () -> assertTrue(runtime.isStopped(), "Конструктор не должен запускать сервер"),
          () -> assertTrue(runtime.localPort() < 0, "До запуска порт не должен быть открыт"));
      assertDoesNotThrow(runtime::close, "Можно закрыть ещё не запущенный сервер");
    }
  }

  @Test
  @DisplayName("Занятый порт вызывает ошибку привязки и допускает последующую остановку")
  void shouldStopAfterFailedStart() throws Exception {
    try (var occupied = new ServerSocket()) {
      occupied.bind(new InetSocketAddress(HOST, 0));
      var runtime = new JettyRuntime(HOST, occupied.getLocalPort(), new HealthHandler());

      try (runtime) {
        var failure = assertThrows(Exception.class, runtime::start,
            "Запуск на занятом порту должен завершиться ошибкой");
        assertTrue(hasBindCause(failure), "Причиной ошибки должна быть привязка порта");
      }

      assertAll(
          () -> assertTrue(runtime.isStopped(),
              "После неудачного запуска close должен остановить компоненты"),
          () -> assertTrue(runtime.localPort() < 0,
              "После неудачного запуска connector не должен владеть открытым портом"));
    }
  }

  @Test
  @DisplayName("Ожидание блокируется до остановки и завершается после close")
  void shouldAwaitTermination() throws Exception {
    try (var runtime = new JettyRuntime(HOST, 0, new HealthHandler())) {
      runtime.start();
      var entered = new CountDownLatch(1);
      var waiting = new FutureTask<Void>(() -> {
        entered.countDown();
        runtime.awaitTermination();
        return null;
      });
      Thread.ofVirtual().start(waiting);
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS), "Поток ожидания должен запуститься");
        assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS),
            "Ожидание не должно завершаться, пока сервер работает");
        runtime.close();
        assertDoesNotThrow(() -> waiting.get(5, TimeUnit.SECONDS),
            "После остановки поток должен выйти из awaitTermination");
      } finally {
        waiting.cancel(true);
      }
    }
  }

  private static HttpClient newClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  private static HttpResponse<String> get(HttpClient client, int port, String path)
      throws Exception {
    var request = HttpRequest.newBuilder()
        .uri(URI.create("http://" + HOST + ":" + port + path))
        .timeout(Duration.ofSeconds(5))
        .GET()
        .build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static boolean hasBindCause(Throwable failure) {
    // Jetty может оборачивать сетевую ошибку; проверяем причину, а не текст сообщения ОС.
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof BindException) {
        return true;
      }
    }
    return false;
  }
}
