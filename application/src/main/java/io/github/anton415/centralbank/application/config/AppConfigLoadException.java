package io.github.anton415.centralbank.application.config;

/**
 * Ожидаемая ошибка загрузки конфигурации с безопасным сообщением для стартовой диагностики.
 *
 * <p>Загрузчик не переносит в это исключение исходные значения настроек и причины сторонних ошибок.
 */
public final class AppConfigLoadException extends Exception {

  /**
   * Создаёт ошибку с описанием проблемы без значений конфигурации.
   *
   * @param message безопасное сообщение с именем ключа, правилом или путём к файлу
   */
  public AppConfigLoadException(String message) {
    super(message);
  }
}
