package pl.marcinzygmunt;

import io.micronaut.runtime.Micronaut;

/**
 * Entrypoint for the Prometheus MCP server.
 *
 * <p>Exposes a Model Context Protocol server (HTTP / streamable transport on {@code /mcp}) that
 * dynamically discovers the metrics available in a Prometheus instance and registers one MCP tool
 * per metric, plus a few helper tools for arbitrary PromQL.
 */
public class Application {
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
