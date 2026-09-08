package io.github.anton415.centralbank.application.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Проверяет контракт конфигурации: допустимые значения, нормализацию и понятные ошибки.
 *
 * <p>В каждом негативном сценарии некорректно только одно поле,
 * чтобы причина отказа была однозначной.
 */
@DisplayName("Конфигурация приложения")
class AppConfigTest {
  @Test
  @DisplayName("Сохраняет корректные настройки")
  void preservesValidValues() {
    var config = new AppConfig("Central Bank Sandbox", "127.0.0.1", 8080);

    // assertAll выполняет все проверки группы и собирает их ошибки в один отчёт.
    assertAll(
        "Корректные настройки должны сохраняться без изменений",
        () -> assertEquals("Central Bank Sandbox", config.name(),
            "Название приложения должно сохраняться целиком"),
        () -> assertEquals("127.0.0.1", config.host(),
            "Адрес сервера должен совпадать с переданным значением"),
        () -> assertEquals(8080, config.port(),
            "Порт сервера должен совпадать с переданным значением"));
  }

  @DisplayName("Удаляет пробельные символы по краям названия и адреса")
  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("whitespaceSamples")
  void stripsSurroundingWhitespace(String whitespace) {
    var config = new AppConfig(
        whitespace + "Central Bank Sandbox" + whitespace,
        whitespace + "127.0.0.1" + whitespace,
        8080);

    assertAll(
        "Нормализация должна затрагивать только края строк",
        () -> assertEquals("Central Bank Sandbox", config.name(),
            "Пробелы по краям названия должны удаляться, а внутри — сохраняться"),
        () -> assertEquals("127.0.0.1", config.host(),
            "Пробельные символы по краям адреса должны удаляться"));
  }

  // Источники складываются: null, пустая строка и три значения из whitespaceSamples.
  @DisplayName("Отклоняет отсутствующее, пустое или пробельное название")
  @ParameterizedTest(name = "[{index}] название: {0}")
  @NullAndEmptySource
  @MethodSource("whitespaceSamples")
  void rejectsBlankName(String name) {
    var exception = assertThrows(
        IllegalArgumentException.class,
        () -> new AppConfig(name, "127.0.0.1", 8080),
        "Незаполненное название должно препятствовать созданию конфигурации");

    // assertThrows возвращает исключение, поэтому отдельно проверяем его диагностику.
    assertTrue(exception.getMessage().contains("application.name"),
        () -> "Ошибка должна указывать ключ application.name, получено: " + exception.getMessage());
  }

  @DisplayName("Отклоняет отсутствующий, пустой или пробельный адрес")
  @ParameterizedTest(name = "[{index}] адрес: {0}")
  @NullAndEmptySource
  @MethodSource("whitespaceSamples")
  void rejectsBlankHost(String host) {
    var exception = assertThrows(
        IllegalArgumentException.class,
        () -> new AppConfig("Sandbox", host, 8080),
        "Незаполненный адрес должен препятствовать созданию конфигурации");

    assertTrue(exception.getMessage().contains("server.host"),
        () -> "Ошибка должна указывать ключ server.host, получено: " + exception.getMessage());
  }

  // Проверяем обе допустимые границы: это обнаруживает случайную замену < на <= или > на >=.
  @DisplayName("Принимает порты на границах допустимого диапазона")
  @ParameterizedTest(name = "[{index}] порт = {0}")
  @ValueSource(ints = {1, 65535})
  void acceptsBoundaryPorts(int port) {
    var config = new AppConfig("Sandbox", "127.0.0.1", port);

    assertEquals(port, config.port(),
        "Граничный допустимый порт должен сохраняться без изменения");
  }

  @DisplayName("Отклоняет порты вне диапазона 1–65535")
  @ParameterizedTest(name = "[{index}] порт = {0}")
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 65536, Integer.MAX_VALUE})
  void rejectsOutOfRangePorts(int port) {
    var exception = assertThrows(
        IllegalArgumentException.class,
        () -> new AppConfig("Sandbox", "127.0.0.1", port),
        () -> "Порт " + port + " вне диапазона 1–65535 должен отклоняться");

    assertTrue(exception.getMessage().contains("server.port"),
        () -> "Ошибка должна указывать ключ server.port, получено: " + exception.getMessage());
  }

  /**
   * Возвращает те же пробельные строки для проверок обязательности и нормализации.
   *
   * <p>При стандартном жизненном цикле JUnit локальный MethodSource должен быть static.
   * Named задаёт подпись в отчёте, а в параметр теста JUnit передаёт саму строку.
   */
  private static Stream<Named<String>> whitespaceSamples() {
    return Stream.of(
        named("Обычный пробел", " "),
        named("Табуляция и переводы строк", "\t\r\n"),
        // EM SPACE проверяет поддержку Unicode-пробелов, которую простая trim() не обеспечивает.
        named("Unicode-пробел EM SPACE (U+2003)", Character.toString(0x2003)));
  }
}
