package io.mycat.vertx;

import io.mycat.config.ServerConfig;
import io.vertx.core.net.NetServerOptions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VertxMycatServerIdleTest {

    @Test
    void configuresServerIdleTimeoutInSeconds() {
        NetServerOptions options = new NetServerOptions();
        ServerConfig config = new ServerConfig();
        config.setIdleTimeout(2);

        VertxMycatServer.configureIdleTimeout(options, config);

        assertEquals(2, options.getIdleTimeout());
        assertEquals(TimeUnit.SECONDS, options.getIdleTimeoutUnit());
    }

    @Test
    void nonPositiveIdleTimeoutDisablesServerIdleTimer() {
        NetServerOptions options = new NetServerOptions();
        ServerConfig config = new ServerConfig();
        config.setIdleTimeout(0);

        VertxMycatServer.configureIdleTimeout(options, config);

        assertEquals(0, options.getIdleTimeout());
    }
}
