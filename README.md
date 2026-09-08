# Central Bank Sandbox

[![Quality Gate](https://github.com/anton415/central-bank/actions/workflows/quality-gate.yml/badge.svg?branch=main)](https://github.com/anton415/central-bank/actions/workflows/quality-gate.yml)

Educational modular monolith with explicit architectural boundaries.

## Project structure

The root `pom.xml` is the shared Maven parent and reactor aggregator.
`application` is currently the only code module. It contains the application
entry point, manual composition root, configuration, local web runtime, and
the initial Vaadin status page.

New Maven modules are introduced when concrete business capabilities need their
own boundaries. A business module can contain its domain rules, use cases, UI,
and persistence adapters, separated into Java packages. Empty modules are not
created in advance for technologies or future features.

See [the architecture decision](docs/architecture.md) for module responsibilities
and dependency rules.

## Configuration loading

`AppConfigLoader` reads a UTF-8 INI file and returns a validated `AppConfig`.
The required keys are `application.name`, `server.host`, and `server.port`;
each must have exactly one value. Names and addresses must be nonblank,
and the port must be an integer from 1 to 65535. Variable expressions are
treated as literal text.

Use `application.example.ini` as a starting point. The local `application.ini`
file is ignored by Git. Loading failures are reported as `AppConfigLoadException`
without including configuration values in the message or exception chain.

## Local launch

Run Maven with JDK 25 (`JAVA_HOME` must point to that JDK). On macOS with Homebrew:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
```

Create `application.ini` from `application.example.ini` if you do not already
have local settings. From the repository root, launch with:

```bash
./mvnw -pl application process-classes exec:exec
```

The exec plugin starts a separate JVM with the runtime dependencies, including
Logback. The `process-classes` phase also prepares the Vaadin production frontend;
using only `compile` is insufficient for a fresh checkout. The first build needs
network access to download dependencies and the frontend bundle. Its working
directory is the repository root. To select another INI
file, use `-Dapp.config=/path/to/application.ini`; relative paths are resolved
from the repository root. For a first run using the tracked example directly:

```bash
./mvnw -pl application process-classes exec:exec -Dapp.config=application.example.ini
```

With the example settings, open **http://127.0.0.1:8080/** in a browser.
The page shows `application.name` and the status “Работает”. Check the HTTP
endpoint in another terminal:

```bash
curl -i http://127.0.0.1:8080/health
```

Expect HTTP 200 and `UP`. Stop with Ctrl+C. Jetty's JVM shutdown hook stops the
server; the port should then be available for another launch.

`ApplicationMain` is the composition root: it creates the loader, handler, and
runtime explicitly. `run()` returns a result for testing; only `main()` calls
`System.exit()` on failure. Application exit codes are 2 for invalid arguments
or configuration, 1 for runtime failure, and 0 after normal completion.
Signal termination and the Maven wrapper may report different process codes.

## Startup diagnostics

Application code uses SLF4J with Logback as the runtime provider.
`application/src/main/resources/logback.xml` configures UTF-8 output to stderr
at the INFO threshold.

`StartupDiagnostics` receives its logger through the constructor. A successful
configuration load emits `event=configuration_loaded` at INFO with only the port
and Java feature version. A failed load emits `event=configuration_load_failed`
at ERROR with the safe message supplied by `AppConfigLoader`.

Failure messages have CR/LF escaped and are logged without the exception object.
This prevents attached causes and suppressed exceptions from entering the output.
Escaping line breaks does not redact secrets: callers must supply the loader's
safe diagnostics, not arbitrary external error messages.

`event=application_started port=...` is emitted at INFO only after Jetty starts.
Invalid arguments emit `event=arguments_invalid reason=expected_one_config_path`
at ERROR: the application expects one nonblank, valid path to an INI file.
Runtime failures emit `event=application_failed reason=server_lifecycle_failed`
at ERROR without a third-party exception message. For a startup failure, check
the configured address and whether another process occupies the port.
Jetty also emits its own lifecycle messages through SLF4J.

## Embedded HTTP runtime

`JettyRuntime` assembles Jetty without starting it in the constructor. Call
`start()` to open the configured address and port, and use try-with-resources
to call `close()` even after a failed start. Closing stops the server and releases
its connector. `localPort()` returns the actual bound port after startup.
`awaitTermination()` waits for shutdown without a polling loop. JVM shutdown
also stops Jetty through its built-in hook. An interrupted application thread
closes the runtime and restores the interruption flag before returning code 1.

`HealthHandler` serves `/health` with HTTP 200, `text/plain; charset=UTF-8`,
and the body `UP` followed by a newline. Other paths are passed to the Vaadin
servlet context by an explicit `Handler.Sequence`.
This endpoint checks HTTP availability; it does not check a database or business
dependencies.

## Vaadin status page

Vaadin 25.2.7 runs on Jetty's Servlet 6.1 implementation without Spring.
The Maven build prepares production assets; no Vaadin development server is
started with the application. JUnit, Jetty, and Logback versions are managed
explicitly to keep their artifacts aligned with the existing project stack.

`ApplicationMain` passes a `Supplier<StatusView>` to `StatusServlet`.
The servlet registers the root route explicitly and uses Vaadin's instantiator
extension point to create a fresh view through that supplier. The view receives
only the application name and inserts it as text, never as HTML. It does not
read INI files or access the full configuration.

Public CSS and the favicon live under `META-INF/resources`; the servlet context
publishes only that directory. Local INI files and the repository root are not
web resources. Generated frontend files are ignored by Git and recreated by Maven.

The visible status confirms availability of the application interface. It does
not represent database or other external dependency health checks.

Runtime smoke tests bind to `127.0.0.1` with port `0`, allowing the OS to select
a free port. User configuration still requires a port from 1 to 65535.
Tests cover real HTTP responses, port release, repeated closing, and cleanup
after an occupied-port startup failure. Launcher tests check exit codes in a
separate JVM. On Linux and macOS, a process smoke test additionally checks HTTP,
SIGTERM shutdown, and port release.

## Local verification

JDK 25 must be installed and discoverable by Maven Toolchains.

Linux and macOS:

```bash
./mvnw verify
```

The command compiles the application, runs unit and HTTP smoke tests, checks Java style,
and rejects forbidden framework dependencies.
