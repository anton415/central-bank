package io.github.anton415.centralbank.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Проверяет версию JVM, в которой Maven фактически запускает тесты. */
@DisplayName("Java toolchain")
class JavaToolchainTest {

  @Test
  @DisplayName("Запускает тесты на Java 25")
  void runsTestsOnRequiredJavaVersion() {
    assertEquals(25, Runtime.version().feature(),
        "Тесты должны выполняться на Java 25, выбранной для проекта");
  }
}
