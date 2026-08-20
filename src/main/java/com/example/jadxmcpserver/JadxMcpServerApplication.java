package com.example.jadxmcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.logging.Logger;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;

/**
 * The jadx analysis daemon.
 *
 * Despite the name this process no longer speaks MCP. The protocol lives in
 * mcp_server/jadx_mcp.py; this is a plain local HTTP service that owns
 * decompilation and keeps loaded APKs warm. The class name is kept because it
 * is the jar's Main-Class and appears in every launch script.
 *
 * Two things deliberately absent, both of which caused the outage this rewrite
 * followed:
 *
 *  - No MCP transport. The Java SDK's stdio transport emits responses onto a
 *    unicast Reactor sink that is unsafe for concurrent emission, so
 *    simultaneous tool completions killed it (java-sdk #686, still open).
 *  - No Thread.currentThread().join(). That parked main forever, so when the
 *    transport died the JVM stayed alive holding its socket open and clients
 *    hung indefinitely. A servlet container keeps itself alive, and when it
 *    stops the process exits and the Python supervisor notices via /health.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        R2dbcAutoConfiguration.class,
        BatchAutoConfiguration.class
})
public class JadxMcpServerApplication {

    private static final Logger logger = Logger.getLogger(JadxMcpServerApplication.class.getName());

    public static void main(String[] args) {
        // Before run(): Spring settles logging.file.name off
        // ApplicationEnvironmentPreparedEvent, so the port-derived path has to
        // be published before the first appender opens. See DaemonLogFile --
        // without it every daemon on the machine appends to one shared file.
        DaemonLogFile.install(args);
        SpringApplication.run(JadxMcpServerApplication.class, args);
        logger.info("JADX analysis daemon started; log: "
                + System.getProperty(DaemonLogFile.PROPERTY, DaemonLogFile.DEFAULT_PATH));
    }

    @Bean
    public JadxApkAnalyzerAPI jadxApkAnalyzerAPI() {
        return new JadxApkAnalyzerAPI();
    }
}
