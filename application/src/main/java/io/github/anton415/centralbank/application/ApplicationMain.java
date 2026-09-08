package io.github.anton415.centralbank.application;

import io.github.anton415.centralbank.application.bootstrap.StartupDiagnostics;
import io.github.anton415.centralbank.application.config.AppConfig;
import io.github.anton415.centralbank.application.config.AppConfigLoadException;
import io.github.anton415.centralbank.application.config.AppConfigLoader;
import io.github.anton415.centralbank.application.runtime.HealthHandler;
import io.github.anton415.centralbank.application.runtime.JettyRuntime;
import io.github.anton415.centralbank.application.runtime.VaadinHandlerFactory;
import io.github.anton415.centralbank.application.ui.StatusServlet;
import io.github.anton415.centralbank.application.ui.StatusView;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.eclipse.jetty.server.Handler;
import org.slf4j.LoggerFactory;

/** Точка входа и явной сборки приложения. */
public final class ApplicationMain {

  private ApplicationMain() {
  }

  /** Запускает приложение с единственным аргументом — путём к INI-файлу. */
  public static void main(String[] args) {
    var diagnostics = new StartupDiagnostics(
        LoggerFactory.getLogger(ApplicationMain.class));

    int exitCode = run(args, diagnostics);

    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  /**
   * Загружает настройки, собирает компоненты и ожидает остановки сервера.
   *
   * @param args аргументы командной строки, содержащие ровно один путь
   * @param diagnostics диагностика с logger, предоставленным точкой входа или тестом
   * @return 0 при нормальном завершении, 2 при ошибке входных данных, 1 при ошибке runtime
   */
  public static int run(String[] args, StartupDiagnostics diagnostics) {
    if (args.length != 1 || args[0].isBlank()) {
      diagnostics.argumentsInvalid();
      return 2;
    }

    AppConfig config;
    try {
      config = new AppConfigLoader().load(Path.of(args[0]));
    } catch (InvalidPathException exception) {
      // InvalidPathException содержит исходный аргумент: не передаём её в журнал.
      diagnostics.argumentsInvalid();
      return 2;
    } catch (AppConfigLoadException exception) {
      diagnostics.configurationFailed(exception);
      return 2;
    }
    diagnostics.configurationLoaded(config);

    // Объекты создаются явно; try закрывает runtime и при частично выполненном запуске.
    try (var runtime = new JettyRuntime(config.host(), config.port(), new Handler.Sequence(
        new HealthHandler(),
        VaadinHandlerFactory.create(new StatusServlet(() -> new StatusView(config.name())))))) {
      runtime.start();
      diagnostics.applicationStarted(runtime.localPort());
      runtime.awaitTermination();
    } catch (InterruptedException exception) {
      // Прерывание — сигнал вызывающему коду, его нельзя молча терять.
      Thread.currentThread().interrupt();
      diagnostics.runtimeFailed();
      return 1;
    } catch (Exception exception) {
      // Причины Jetty и подавленные ошибки close могут содержать значения настроек.
      diagnostics.runtimeFailed();
      return 1;
    }
    return 0;
  }
}
