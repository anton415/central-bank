package io.github.anton415.centralbank.application.runtime;

import com.vaadin.flow.di.LookupInitializer;
import com.vaadin.flow.server.VaadinServlet;
import com.vaadin.flow.server.startup.LookupServletContainerInitializer;
import java.util.Objects;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.util.resource.ResourceFactory;

/** Создаёт servlet-контекст Vaadin для встроенного Jetty без сканирования приложения и DI. */
public final class VaadinHandlerFactory {

  private VaadinHandlerFactory() {
  }

  /**
   * Регистрирует переданный servlet и инфраструктуру Vaadin, не запуская сервер.
   *
   * @param servlet явно собранный servlet приложения
   * @return контекст с поддержкой HTTP-сессий
   */
  public static ServletContextHandler create(VaadinServlet servlet) {
    var context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath("/");
    context.setClassLoader(VaadinHandlerFactory.class.getClassLoader());
    // Публикуем только каталог веб-ресурсов, а не корень проекта с локальным INI.
    context.setBaseResource(ResourceFactory.of(context).newResource(Objects.requireNonNull(
        VaadinHandlerFactory.class.getResource("/META-INF/resources/"), "web resources")));
    // В embedded-режиме явно запускаем штатный bootstrap внутренних сервисов Vaadin.
    context.addServletContainerInitializer(
        new LookupServletContainerInitializer(), LookupInitializer.class);

    var holder = new ServletHolder(servlet);
    holder.setInitOrder(1);
    holder.setAsyncSupported(true);
    holder.setInitParameter("productionMode", "true");
    context.addServlet(holder, "/*");
    return context;
  }
}
