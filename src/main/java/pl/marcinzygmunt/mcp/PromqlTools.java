package pl.marcinzygmunt.mcp;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.mcp.annotations.Tool;
import io.micronaut.mcp.annotations.ToolArg;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pl.marcinzygmunt.prometheus.PrometheusClient;

import java.util.function.Supplier;

/**
 * Static MCP tools (the escape hatch the per-metric tools can't cover): arbitrary PromQL plus a
 * trigger to re-scan Prometheus for new metrics.
 */
@Slf4j
@Singleton
public class PromqlTools {

    private final PrometheusClient client;
    private final MetricToolRegistrar registrar;

    public PromqlTools(PrometheusClient client, MetricToolRegistrar registrar) {
        this.client = client;
        this.registrar = registrar;
    }

    @Tool(name = "promql_query",
            description = "Run an arbitrary instant PromQL query against Prometheus (/api/v1/query). "
                    + "Use this for rate(), aggregations (sum/avg by), histogram_quantile(), joins, etc. "
                    + "Returns the raw Prometheus JSON response.")
    public String promqlQuery(
            @ToolArg(name = "query", description = "PromQL expression, e.g. rate(loadgen_http_calls_total[1m])")
            String query,
            @Nullable @ToolArg(name = "time", description = "Optional evaluation timestamp (RFC3339 or unix seconds).")
            String time) {
        return safe(query, () -> client.query(query, (time == null || time.isBlank()) ? null : time));
    }

    @Tool(name = "promql_query_range",
            description = "Run an arbitrary PromQL range query against Prometheus (/api/v1/query_range). "
                    + "Returns a time-series matrix as raw Prometheus JSON.")
    public String promqlQueryRange(
            @ToolArg(name = "query", description = "PromQL expression, e.g. histogram_quantile(0.95, sum by (le) (rate(loadgen_http_call_duration_seconds_bucket[5m])))")
            String query,
            @ToolArg(name = "start", description = "Start time (RFC3339 or unix seconds).")
            String start,
            @ToolArg(name = "end", description = "End time (RFC3339 or unix seconds).")
            String end,
            @ToolArg(name = "step", description = "Resolution step, e.g. 15s, 1m.")
            String step) {
        return safe(query, () -> client.queryRange(query, start, end, step));
    }

    @Tool(name = "refresh_metric_tools",
            description = "Re-scan Prometheus for available metric names and (re)register one MCP tool per metric. "
                    + "Returns a summary of how many tools are now active.")
    public String refreshMetricTools() {
        MetricToolRegistrar.SyncResult r = registrar.sync();
        return "Metric tools: " + r.active() + " active (" + r.added() + " added, " + r.removed() + " removed) "
                + "out of " + r.totalDiscovered() + " metrics discovered"
                + (r.truncated() ? " [TRUNCATED to configured maxTools]" : "") + ".";
    }

    private static final int MAX_LOG_LENGTH = 500;

    private static String safe(String query, Supplier<String> call) {
        log.info("PromQL query: {}", query);
        try {
            var response = call.get();
            log.info("PromQL response [{}]: {}", query,
                    response.length() > MAX_LOG_LENGTH ? response.substring(0, MAX_LOG_LENGTH) + "…" : response);
            return response;
        } catch (HttpClientResponseException e) {
            log.warn("Prometheus error for query [{}]: {}", query, e.getMessage());
            return "Prometheus error: " + e.getResponse().getBody(String.class).orElse(e.getMessage());
        } catch (Exception e) {
            log.warn("Error executing query [{}]: {}", query, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }
}
