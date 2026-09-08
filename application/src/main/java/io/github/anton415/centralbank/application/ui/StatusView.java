package io.github.anton415.centralbank.application.ui;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import java.util.Objects;

/** Стартовая страница; получает только данные, предназначенные для отображения пользователю. */
@Route("")
@StyleSheet("styles/status.css")
public final class StatusView extends Main implements HasDynamicTitle {

  private final String applicationName;

  /**
   * Создаёт отдельное дерево компонентов для текущего пользователя.
   *
   * @param applicationName проверенное название приложения из конфигурации
   */
  public StatusView(String applicationName) {
    this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
    addClassName("status-page");
    getElement().setAttribute("lang", "ru");

    var brand = new Div(new Span("ЦБ"), new Span("Учебный банковский проект"));
    brand.addClassName("status-brand");

    var heading = new H1(applicationName);
    heading.setId("application-name");
    var eyebrow = new Paragraph("СОСТОЯНИЕ ПРИЛОЖЕНИЯ");
    eyebrow.addClassName("status-eyebrow");
    var introduction = new Paragraph("Приложение запущено и готово к работе.");
    introduction.addClassName("status-introduction");

    var badge = new Span("Работает");
    badge.setId("application-status");
    badge.addClassName("status-badge");
    var card = new Div(new H2("Всё готово"), badge,
        new Paragraph("Веб-интерфейс доступен. Это стартовая страница приложения."));
    card.addClassName("status-card");
    card.getElement().setAttribute("role", "status");

    var health = new Anchor("/health", "Проверить HTTP-сервер →");
    health.addClassName("status-link");
    // Проверка возвращает обычный HTTP-ответ, поэтому её не должен перехватывать роутер Vaadin.
    health.getElement().setAttribute("router-ignore", true);
    var note = new Paragraph("Статус подтверждает доступность приложения. "
        + "Проверки внешних сервисов пока не подключены.");
    note.addClassName("status-note");

    add(brand, eyebrow, heading, introduction, card, health, note);
  }

  @Override
  public String getPageTitle() {
    return applicationName + " — Состояние приложения";
  }
}
