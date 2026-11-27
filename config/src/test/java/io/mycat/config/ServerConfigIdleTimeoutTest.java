package io.mycat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerConfigIdleTimeoutTest {

    @Test
    void defaultIdleTimeoutIsFiveMinutes() {
        assertEquals(300, new ServerConfig().getIdleTimeout());
    }

    @Test
    void idleTimeoutCanBeDisabled() {
        ServerConfig config = new ServerConfig();
        config.setIdleTimeout(0);
        assertEquals(0, config.getIdleTimeout());
    }
}
