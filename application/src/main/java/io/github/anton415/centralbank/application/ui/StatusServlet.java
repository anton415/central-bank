package io.github.anton415.centralbank.application.ui;

import com.vaadin.flow.di.DefaultInstantiator;
import com.vaadin.flow.di.Instantiator;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinServlet;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Соединяет жизненный цикл Vaadin с явной фабрикой прикладной страницы. */
public final class StatusServlet extends VaadinServlet {

  private final Supplier<StatusView> viewFactory;

  /**
   * Принимает фабрику из composition root, не загружая конфигурацию самостоятельно.
   *
   * @param viewFactory фабрика новой страницы для каждой навигации
   */
  public StatusServlet(Supplier<StatusView> viewFactory) {
    this.viewFactory = Objects.requireNonNull(viewFactory, "viewFactory");
  }

  @Override
  protected VaadinServletService createServletService(DeploymentConfiguration configuration)
      throws ServiceException {
    var service = new VaadinServletService(this, configuration) {
      @Override
      protected Instantiator createInstantiator() {
        return new DefaultInstantiator(this) {
          @Override
          public Stream<VaadinServiceInitListener> getServiceInitListeners() {
            VaadinServiceInitListener pageSettings = event ->
                event.addIndexHtmlRequestListener(StatusServlet::configurePage);
            return Stream.concat(super.getServiceInitListeners(), Stream.of(pageSettings));
          }

          @Override
          public <T> T getOrCreate(Class<T> type) {
            if (type == StatusView.class) {
              return type.cast(viewFactory.get());
            }
            return super.getOrCreate(type);
          }
        };
      }
    };
    service.init();
    return service;
  }

  @Override
  protected void servletInitialized() {
    // Регистрация явная: runtime не сканирует весь classpath в поисках прикладных маршрутов.
    RouteConfiguration.forRegistry(ApplicationRouteRegistry.getInstance(getService().getContext()))
        .setRoute("", StatusView.class);
  }

  private static void configurePage(IndexHtmlResponse response) {
    response.getDocument().selectFirst("html").attr("lang", "ru");
    response.getDocument().head().appendElement("link")
        .attr("rel", "icon").attr("type", "image/svg+xml").attr("href", "favicon.svg");
  }
}
