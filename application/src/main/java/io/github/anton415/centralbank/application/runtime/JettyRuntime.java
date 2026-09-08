package io.github.anton415.centralbank.application.runtime;

import java.util.Objects;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * Управляет жизненным циклом встроенного HTTP-сервера.
 *
 * <p>Владелец runtime должен закрыть его даже после ошибки запуска, например через
 * try-with-resources. Загрузка конфигурации и логирование относятся к вызывающему коду.
 */
public final class JettyRuntime implements AutoCloseable {

  private final Server server;
  private final ServerConnector connector;

  /**
   * Собирает компоненты, не открывая сетевой порт.
   *
   * @param host адрес для входящих соединений
   * @param port порт; значение 0 позволяет ОС выбрать свободный порт в тестах
   * @param handler обработчик HTTP-запросов
   */
  public JettyRuntime(String host, int port, Handler handler) {
    Objects.requireNonNull(handler, "handler");
    server = new Server();
    connector = new ServerConnector(server);
    connector.setHost(host);
    connector.setPort(port);
    server.addConnector(connector);
    server.setHandler(handler);
    // JVM shutdown не гарантирует выход основного потока из try-with-resources.
    server.setStopAtShutdown(true);
  }

  /**
   * Запускает сервер и открывает порт до возврата из метода.
   *
   * @throws Exception если один из компонентов не удалось запустить
   */
  public void start() throws Exception {
    server.start();
  }

  /**
   * Возвращает фактический порт, в том числе выбранный ОС при настройке порта 0.
   *
   * @return открытый порт; отрицательное значение, если connector не открыт
   */
  public int localPort() {
    return connector.getLocalPort();
  }

  /** Возвращает true, если сервер находится в состоянии STOPPED. */
  public boolean isStopped() {
    return server.isStopped();
  }

  /**
   * Останавливает компоненты и освобождает порт; допускает повторный вызов.
   *
   * @throws Exception если один из компонентов не удалось остановить
   */
  @Override
  public void close() throws Exception {
    server.stop();
  }

  /**
   * Ожидает завершения работы сервера.
   *
   * @throws InterruptedException если ожидающий поток был прерван
   */
  public void awaitTermination() throws InterruptedException {
    server.join();
  }
}
