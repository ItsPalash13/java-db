package com.example.database;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LaunchConfigTest {

    @Test
    void defaultsWhenNoArgs() {
        LaunchConfig config = LaunchConfig.parse(new String[0]);
        assertEquals(LaunchConfig.DEFAULT_PORT, config.port());
        assertEquals(Path.of("data"), config.dataDir());
    }

    @Test
    void positionalPortKeepsDefaultDataDir() {
        LaunchConfig config = LaunchConfig.parse(new String[]{"9090"});
        assertEquals(9090, config.port());
        assertEquals(Path.of("data"), config.dataDir());
    }

    @Test
    void positionalPortAndDataDir() {
        LaunchConfig config = LaunchConfig.parse(new String[]{"8080", "C:\\stores\\mine"});
        assertEquals(8080, config.port());
        assertEquals(Path.of("C:\\stores\\mine"), config.dataDir());
    }

    @Test
    void positionalDataDirKeepsDefaultPort() {
        LaunchConfig config = LaunchConfig.parse(new String[]{"my-data"});
        assertEquals(LaunchConfig.DEFAULT_PORT, config.port());
        assertEquals(Path.of("my-data"), config.dataDir());
    }

    @Test
    void flagDataDir() {
        LaunchConfig config = LaunchConfig.parse(new String[]{"--data-dir", "stores/prod"});
        assertEquals(LaunchConfig.DEFAULT_PORT, config.port());
        assertEquals(Path.of("stores/prod"), config.dataDir());
    }

    @Test
    void flagPortAndDataDir() {
        LaunchConfig config = LaunchConfig.parse(new String[]{"--port", "7070", "--data-dir", "d"});
        assertEquals(7070, config.port());
        assertEquals(Path.of("d"), config.dataDir());
    }

    @Test
    void missingDataDirValue() {
        assertThrows(IllegalArgumentException.class,
                () -> LaunchConfig.parse(new String[]{"--data-dir"}));
    }

    @Test
    void unknownOption() {
        assertThrows(IllegalArgumentException.class,
                () -> LaunchConfig.parse(new String[]{"--help"}));
    }
}
