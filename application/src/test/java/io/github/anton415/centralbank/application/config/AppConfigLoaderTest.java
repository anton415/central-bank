package io.github.anton415.centralbank.application.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Проверяет весь путь от временного INI-файла до модели или безопасной ошибки загрузки.
 *
 * <p>Каждый тест получает собственный каталог и не использует локальную конфигурацию разработчика.
 */
@DisplayName("Загрузка INI-конфигурации")
class AppConfigLoaderTest {

  private static final String VALID_INI =
      """
      ; Настройки учебного приложения
      [application]
      name = Учебный банк

      [server]
      host = 127.0.0.1
      port = 8080
      """;

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("Загружает настройки из INI-файла в UTF-8")
  void loadsValidIni() throws Exception {
    Path file = writeIni(VALID_INI);

    var config = new AppConfigLoader().load(file);

    assertAll(
        "Настройки должны соответствовать содержимому INI",
        () -> assertEquals("Учебный банк", config.name(),
            "Название на кириллице должно корректно читаться в UTF-8"),
        () -> assertEquals("127.0.0.1", config.host(),
            "Адрес должен читаться из секции server"),
        () -> assertEquals(8080, config.port(),
            "Текстовое значение порта должно преобразовываться в int"));
  }

  @Test
  @DisplayName("Нормализует пробелы в значениях, заключённых в кавычки")
  void normalizesQuotedValues() throws Exception {
    Path file = writeIni(VALID_INI
        .replace("name = Учебный банк", "name = \" Учебный банк \"")
        .replace("host = 127.0.0.1", "host = \" 127.0.0.1 \"")
        .replace("port = 8080", "port = \" 8080 \""));

    var config = new AppConfigLoader().load(file);

    assertEquals(new AppConfig("Учебный банк", "127.0.0.1", 8080), config,
        "Пробелы должны удаляться перед проверкой модели и преобразованием порта");
  }

  @DisplayName("Отклоняет отсутствие обязательной настройки")
  @ParameterizedTest(name = "[{index}] отсутствует {0}")
  @MethodSource("requiredSettings")
  void rejectsMissingSetting(String key, String line) throws Exception {
    Path file = writeIni(VALID_INI.replace(line + "\n", ""));

    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(file),
        "Отсутствие обязательного ключа должно прерывать загрузку");

    assertEquals(key + " is required", exception.getMessage(),
        "Ошибка должна указывать отсутствующий обязательный ключ");
  }

  @DisplayName("Отклоняет повторение обязательной настройки")
  @ParameterizedTest(name = "[{index}] повторяется {0}")
  @MethodSource("requiredSettings")
  void rejectsDuplicateSetting(String key, String line) throws Exception {
    Path file = writeIni(VALID_INI.replace(line, line + "\n" + line));

    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(file),
        "Повторяющийся ключ должен отклоняться даже при одинаковых значениях");

    assertEquals(key + " must have exactly one value", exception.getMessage(),
        "Ошибка должна указывать ключ с несколькими значениями");
  }

  @DisplayName("Отклоняет порт, который невозможно преобразовать в int")
  @ParameterizedTest(name = "[{index}] текст порта: {0}")
  @EmptySource
  @ValueSource(strings = {"abc", "8.5", "2147483648", "  "})
  void rejectsInvalidPortText(String port) throws Exception {
    Path file = writeIni(VALID_INI.replace("port = 8080", "port = " + port));

    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(file),
        "Некорректное число должно приводить к ошибке загрузки конфигурации");

    assertEquals("server.port must be an integer", exception.getMessage(),
        "Ошибка должна объяснять требование к формату порта");
  }

  @DisplayName("Передаёт проверку значений модели AppConfig")
  @ParameterizedTest(name = "[{index}] нарушено правило {0}")
  @MethodSource("invalidModelSettings")
  void reportsModelValidationFailure(String key, String content) throws Exception {
    Path file = writeIni(content);

    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(file),
        "Ошибка модели должна выходить через контракт загрузчика");

    assertTrue(exception.getMessage().contains(key),
        () -> "Ошибка должна указывать ключ " + key + ", получено: " + exception.getMessage());
  }

  @Test
  @DisplayName("Объясняет отсутствие файла конфигурации")
  void rejectsMissingFile() {
    Path file = tempDir.resolve("missing.ini");

    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(file),
        "Несуществующий файл должен приводить к ошибке загрузки");

    assertEquals("Cannot read configuration file: " + file, exception.getMessage(),
        "Диагностика должна указывать файл, который не удалось прочитать");
  }

  @Test
  @DisplayName("Отклоняет каталог вместо INI-файла")
  void rejectsDirectory() {
    // Каталог даёт воспроизводимую ошибку чтения без изменения прав доступа к файлам.
    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(tempDir),
        "Путь к каталогу должен приводить к ошибке чтения файла");

    assertEquals("Cannot read configuration file: " + tempDir, exception.getMessage(),
        "Ошибка чтения должна содержать переданный путь");
  }

  @Test
  @DisplayName("Отклоняет файл с некорректной кодировкой UTF-8")
  void rejectsInvalidUtf8() throws Exception {
    Path file = tempDir.resolve("invalid-encoding.ini");
    // После начального байта UTF-8 стоит недопустимый байт продолжения.
    Files.write(file, new byte[] {(byte) 0xc3, 0x28});

    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(file),
        "Повреждённый UTF-8 должен приводить к ошибке чтения");

    assertEquals("Cannot read configuration file: " + file, exception.getMessage(),
        "Ошибка декодирования должна выходить через контракт загрузчика");
  }

  @Test
  @DisplayName("Не переносит настройки между последовательными загрузками")
  void doesNotReusePreviousSettings() throws Exception {
    var loader = new AppConfigLoader();
    loader.load(writeIni(VALID_INI));
    Path secondFile = tempDir.resolve("second.ini");
    Files.writeString(secondFile, VALID_INI.replace("port = 8080\n", ""),
        StandardCharsets.UTF_8);

    var exception = assertThrows(AppConfigLoadException.class,
        () -> loader.load(secondFile),
        "Отсутствующий порт не должен заимствоваться из предыдущего файла");

    assertEquals("server.port is required", exception.getMessage(),
        "Каждая загрузка должна проверять только содержимое текущего файла");
  }

  @Test
  @DisplayName("Сохраняет выражения подстановки как буквальный текст")
  void preservesLiteralValues() throws Exception {
    Path file = writeIni(VALID_INI.replace("Учебный банк", "${server.host}"));

    var config = new AppConfigLoader().load(file);

    assertEquals("${server.host}", config.name(),
        "Значение настройки не должно автоматически заменяться значением другого ключа");
  }

  @Test
  @DisplayName("Не раскрывает отвергнутое значение даже в полном stack trace")
  void doesNotLeakInvalidValue() throws Exception {
    String marker = "sensitive-test-marker";
    Path file = writeIni(VALID_INI.replace("port = 8080", "port = " + marker));

    var exception = assertThrows(AppConfigLoadException.class,
        () -> new AppConfigLoader().load(file),
        "Текстовый маркер вместо порта должен отклоняться");

    var trace = new StringWriter();
    exception.printStackTrace(new PrintWriter(trace));

    assertAll(
        "Диагностика должна объяснять проблему без исходного значения",
        () -> assertEquals("server.port must be an integer", exception.getMessage(),
            "Сообщение должно содержать только ключ и требование к числу"),
        () -> assertFalse(trace.toString().contains(marker),
            "Stack trace и вложенные исключения не должны раскрывать значение настройки"));
  }

  private Path writeIni(String content) throws IOException {
    Path file = tempDir.resolve("application.ini");
    return Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  /** Возвращает ключ и соответствующую строку файла для независимых проверок каждого параметра. */
  private static Stream<Arguments> requiredSettings() {
    return Stream.of(
        arguments("application.name", "name = Учебный банк"),
        arguments("server.host", "host = 127.0.0.1"),
        arguments("server.port", "port = 8080"));
  }

  /** Проверяет связь с моделью, не повторяя все граничные случаи из AppConfigTest. */
  private static Stream<Arguments> invalidModelSettings() {
    return Stream.of(
        arguments("application.name", VALID_INI.replace("name = Учебный банк", "name =")),
        arguments("server.host", VALID_INI.replace("host = 127.0.0.1", "host =")),
        arguments("server.port", VALID_INI.replace("port = 8080", "port = 65536")));
  }
}
