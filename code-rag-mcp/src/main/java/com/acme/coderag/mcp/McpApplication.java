package com.acme.coderag.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Remote MCP server over streamable HTTP.
 *
 * <p>Deployed to Cloud Run with {@code --no-allow-unauthenticated}: Cloud Run IAM
 * is the outer gate, and a bearer-token filter inside the application is the inner
 * one. Two layers, because the outer one is easy to widen by accident.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class McpApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpApplication.class, args);
    }
}
