package io.github.anton415.centralbank.application.config;

/**
 * Validated settings for application startup.
 *
 * @param name application display name
 * @param host server bind address
 * @param port server listening port
 */
public record AppConfig(String name, String host, int port) {

  /**
   * Validates required settings and removes surrounding whitespace.
   *
   * @throws IllegalArgumentException if name or host is missing or blank, or port is out of range
   */
  public AppConfig {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "application.name must not be blank");
    }
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException(
          "server.host must not be blank");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          "server.port must be between 1 and 65535");
    }

    name = name.strip();
    host = host.strip();
  }
}
