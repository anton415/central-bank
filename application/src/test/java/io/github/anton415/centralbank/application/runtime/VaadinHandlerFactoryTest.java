package io.github.anton415.centralbank.application.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.anton415.centralbank.application.ui.StatusServlet;
import io.github.anton415.centralbank.application.ui.StatusView;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Проверяет инициализацию Vaadin и ручную фабрику страниц в настоящем servlet-контексте. */
@DisplayName("Vaadin во встроенном Jetty")
@Timeout(30)
class VaadinHandlerFactoryTest {

  @Test
  @DisplayName("Отдаёт bootstrap страницы и создаёт независимые компоненты с переданным названием")
  void servesBootstrapAndCreatesViews() throws Exception {
    var servlet = new StatusServlet(() -> new StatusView("Тестовый банк"));
    try (var runtime = new JettyRuntime("127.0.0.1", 0, VaadinHandlerFactory.create(servlet));
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
      runtime.start();
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + runtime.localPort() + "/"))
          .timeout(Duration.ofSeconds(5)).GET().build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertAll(
          () -> assertEquals(200, response.statusCode(), "Корневой путь должен отдавать Vaadin"),
          () -> assertTrue(response.body().contains("VAADIN"),
              "Ответ должен подключать клиентские ресурсы Vaadin"));
      var stylesheet = get(client, runtime.localPort(), "/styles/status.css");
      var favicon = get(client, runtime.localPort(), "/favicon.svg");
      assertAll(
          () -> assertEquals(200, stylesheet.statusCode(), "CSS должен быть доступен браузеру"),
          () -> assertTrue(stylesheet.body().contains(".status-page"),
              "CSS-адрес должен возвращать стили страницы, а не HTML bootstrap"),
          () -> assertEquals(200, favicon.statusCode(), "Иконка должна быть доступна браузеру"),
          () -> assertTrue(favicon.body().contains("<svg"), "Ответ должен содержать SVG-иконку"));
      var instantiator = servlet.getService().getInstantiator();
      var first = instantiator.getOrCreate(StatusView.class);
      var second = instantiator.getOrCreate(StatusView.class);
      assertAll(
          () -> assertEquals("Тестовый банк — Состояние приложения", first.getPageTitle(),
              "Страница должна получать название через фабрику composition root"),
          () -> assertNotSame(first, second,
              "Пользователи не должны разделять одно дерево компонентов Vaadin"));
    }
  }

  private static HttpResponse<String> get(HttpClient client, int port, String path)
      throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .timeout(Duration.ofSeconds(5)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}
