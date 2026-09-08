package io.github.anton415.centralbank.application.bootstrap;

import io.github.anton415.centralbank.application.config.AppConfig;
import io.github.anton415.centralbank.application.config.AppConfigLoadException;
import java.util.Objects;
import org.slf4j.Logger;

/** Создаёт события запуска приложения с явно выбранными диагностическими данными. */
public final class StartupDiagnostics {

  private final Logger logger;

  /**
   * Принимает logger от кода сборки приложения или теста.
   *
   * @param logger logger для стартовых событий
   */
  public StartupDiagnostics(Logger logger) {
    this.logger = Objects.requireNonNull(logger, "logger");
  }

  /**
   * Сообщает о загрузке настроек, включая только порт и основную версию Java.
   *
   * @param config проверенная конфигурация
   */
  public void configurationLoaded(AppConfig config) {
    // Не передаём весь record: его toString() включает все значения настроек.
    logger.info("event=configuration_loaded port={} java={}",
        config.port(), Runtime.version().feature());
  }

  /**
   * Сообщает об ожидаемой ошибке конфигурации без stack trace.
   *
   * @param exception ошибка с безопасным сообщением, сформированным AppConfigLoader
   */
  public void configurationFailed(AppConfigLoadException exception) {
    // Передаём текст, чтобы SLF4J не присоединил Throwable с его причинами и подавленными ошибками.
    logger.error("event=configuration_load_failed reason={}", singleLine(exception.getMessage()));
  }

  /**
   * Сообщает об успешном запуске после открытия сетевого порта.
   *
   * @param port фактически открытый порт
   */
  public void applicationStarted(int port) {
    logger.info("event=application_started port={}", port);
  }

  /** Сообщает об ошибке аргументов, не раскрывая их исходные значения. */
  public void argumentsInvalid() {
    logger.error("event=arguments_invalid reason=expected_one_config_path");
  }

  /** Сообщает об ошибке запуска, ожидания или остановки без стороннего исключения. */
  public void runtimeFailed() {
    logger.error("event=application_failed reason=server_lifecycle_failed");
  }

  private static String singleLine(String message) {
    if (message == null) {
      return "Configuration loading failed";
    }

    // Экранирование CR/LF сохраняет одну строку журнала, но само по себе не скрывает секреты.
    return message.replace("\r", "\\r").replace("\n", "\\n");
  }
}
