package com.example.jadxmcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.logging.Logger;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        R2dbcAutoConfiguration.class,
        BatchAutoConfiguration.class
})
public class JadxMcpServerApplication {
    
    private static final Logger logger = Logger.getLogger(JadxMcpServerApplication.class.getName());

    static {
        // Enable debug logging
        System.setProperty("logging.level.root", "INFO");
        System.setProperty("logging.level.org.springframework", "DEBUG");
        System.setProperty("logging.level.org.springframework.ai.mcp", "DEBUG");
    }

    public static void main(String[] args) {
        logger.info("Starting JADX MCP Server Application...");
        
        try {
            SpringApplication app = new SpringApplication(JadxMcpServerApplication.class);
            app.run(args);
            
            logger.info("JADX MCP Server started successfully");
            
            // Keep the application running
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.severe("Failed to start MCP server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    @Bean
    public ToolCallbackProvider jadxTools(JadxToolService jadxService) {
        logger.info("Registering JADX tools...");
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(jadxService)
                .build();
        logger.info("Registered " + provider.getToolCallbacks() + " tool callbacks");
        return provider;
    }

    @Bean
    public JadxApkAnalyzerAPI jadxApkAnalyzerAPI() {
        return new JadxApkAnalyzerAPI();
    }
}
