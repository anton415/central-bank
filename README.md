# Central Bank Sandbox

[![Quality Gate](https://github.com/anton415/central-bank/actions/workflows/quality-gate.yml/badge.svg?branch=main)](https://github.com/anton415/central-bank/actions/workflows/quality-gate.yml)

Educational modular monolith with explicit architectural boundaries.

## Local verification

JDK 25 must be installed and discoverable by Maven Toolchains.

Linux and macOS:

```bash
./mvnw verify
```

The command compiles all modules, runs unit tests, checks Java style,
and rejects forbidden framework dependencies.
