package io.github.anton415.centralbank.application.ui;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vaadin.flow.dom.Element;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Проверяет прикладное содержимое страницы и безопасную передачу названия в DOM. */
@DisplayName("Стартовая страница состояния")
class StatusViewTest {

  @ParameterizedTest(name = "Название: {0}")
  @ValueSource(strings = {"Central Bank Sandbox", "Учебный банк", "<script>alert('name')</script>"})
  @DisplayName("Показывает название как текст и доступный пользователю статус")
  void displaysNameAsText(String name) {
    var view = new StatusView(name);
    Element heading = childWithId(view, "application-name");
    Element status = childWithId(view, "application-status");

    assertAll(
        () -> assertEquals(name, heading.getText(), "Заголовок должен сохранять название из INI"),
        () -> assertTrue(heading.getChildren().allMatch(Element::isTextNode),
            "Название должно состоять из текстовых узлов, а не HTML или скриптов"),
        () -> assertFalse(heading.hasProperty("innerHTML"),
            "Страница не должна вставлять конфигурацию через innerHTML"),
        () -> assertEquals("Работает", status.getText(), "Статус должен быть понятен пользователю"),
        () -> assertEquals(name + " — Состояние приложения", view.getPageTitle(),
            "Вкладка браузера должна содержать название приложения"),
        () -> assertEquals("ru", view.getElement().getAttribute("lang"),
            "Язык содержимого должен быть задан для вспомогательных технологий"));
  }

  private static Element childWithId(StatusView view, String id) {
    return descendants(view.getElement()).filter(element -> id.equals(element.getAttribute("id")))
        .findFirst().orElseThrow();
  }

  private static Stream<Element> descendants(Element element) {
    return Stream.concat(Stream.of(element),
        element.getChildren().flatMap(StatusViewTest::descendants));
  }
}
