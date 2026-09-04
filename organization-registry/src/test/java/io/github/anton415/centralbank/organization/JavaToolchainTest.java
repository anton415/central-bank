package io.github.anton415.centralbank.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JavaToolchainTest {

    @Test
    void runsTestsOnRequiredJavaVersion() {
        assertEquals(25, Runtime.version().feature());
    }
}
