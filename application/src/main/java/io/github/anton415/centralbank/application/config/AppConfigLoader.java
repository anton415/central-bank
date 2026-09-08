package io.github.anton415.centralbank.application.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.configuration2.INIConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;

/** Загружает обязательные настройки из INI-файла и передаёт их на проверку модели. */
public final class AppConfigLoader {

  /**
   * Читает файл в UTF-8 и возвращает проверенную конфигурацию.
   *
   * @param path путь к INI-файлу
   * @return конфигурация с нормализованными и проверенными значениями
   * @throws AppConfigLoadException если файл недоступен или настройки некорректны
   */
  public AppConfig load(Path path) throws AppConfigLoadException {
    // read() дополняет состояние парсера, поэтому каждой загрузке нужен новый экземпляр.
    var ini = new INIConfiguration();

    try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      ini.read(reader);
    } catch (IOException exception) {
      throw new AppConfigLoadException("Cannot read configuration file: " + path);
    } catch (ConfigurationException exception) {
      // Сообщение и cause стороннего парсера могут содержать исходные значения настроек.
      throw new AppConfigLoadException("Cannot parse configuration file: " + path);
    }

    String name = requiredString(ini, "application.name");
    String host = requiredString(ini, "server.host");
    int port = parsePort(requiredString(ini, "server.port"));

    try {
      return new AppConfig(name, host, port);
    } catch (IllegalArgumentException exception) {
      // У AppConfig контролируемые сообщения: только имя ключа и нарушенное правило.
      throw new AppConfigLoadException(exception.getMessage());
    }
  }

  private String requiredString(INIConfiguration ini, String key) throws AppConfigLoadException {
    // getProperty возвращает исходное значение без подстановки переменных.
    Object value = ini.getProperty(key);

    if (value == null) {
      throw new AppConfigLoadException(key + " is required");
    }

    // Повторяющийся INI-ключ представлен списком, а нам нужна ровно одна строка.
    if (!(value instanceof String text)) {
      throw new AppConfigLoadException(key + " must have exactly one value");
    }

    return text;
  }

  private int parsePort(String text) throws AppConfigLoadException {
    try {
      return Integer.parseInt(text.strip());
    } catch (NumberFormatException exception) {
      // Не сохраняем cause: NumberFormatException включает отвергнутую строку в сообщение.
      throw new AppConfigLoadException("server.port must be an integer");
    }
  }
}
