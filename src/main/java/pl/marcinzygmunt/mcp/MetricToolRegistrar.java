package pl.marcinzygmunt.mcp;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.marcinzygmunt.prometheus.LabelValuesResponse;
import pl.marcinzygmunt.prometheus.MetadataResponse;
import pl.marcinzygmunt.prometheus.MetricMeta;
import pl.marcinzygmunt.prometheus.PrometheusConfig;
import pl.marcinzygmunt.prometheus.PrometheusClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Discovers the metric names exposed by Prometheus and registers one MCP tool per metric on the
 * stateless MCP server at startup. Re-running {@link #sync()} (e.g. via the {@code refresh_metric_tools}
 * tool) diffs against what is already registered and adds/removes tools accordingly.
 *
 * <p>Each per-metric tool runs an instant query of {@code metric{matchers}} by default, with optional
 * arguments to wrap it in {@code rate(...)} or switch to a range query — the LLM composes the rest
 * (aggregations, quantiles, ...) via the helper tools in {@link PromqlTools}.
 */
@Singleton
public class MetricToolRegistrar implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(MetricToolRegistrar.class);

    /** Shared input JSON Schema for every per-metric tool (kept quote-free to stay valid JSON). */
    private static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "matchers": {"type": "string", "description": "PromQL label matchers without braces, e.g. job=load-generator,area=heap"},
                "rate_window": {"type": "string", "description": "Range-vector window for rate(), e.g. 1m or 5m; wraps the metric in rate(metric[window]) - use for counters"},
                "time": {"type": "string", "description": "Evaluation timestamp (RFC3339 or unix seconds); instant query only"},
                "start": {"type": "string", "description": "Range query start (RFC3339 or unix seconds); requires end and step"},
                "end": {"type": "string", "description": "Range query end (RFC3339 or unix seconds); requires start and step"},
                "step": {"type": "string", "description": "Range query resolution step, e.g. 15s or 1m; requires start and end"}
              }
            }
            """;

    private final McpStatelessSyncServer server;
    private final PrometheusClient client;
    private final PrometheusConfig config;
    private final McpJsonMapper jsonMapper;

    /** metric name -> sanitized tool name currently registered. */
    private final Map<String, String> registered = new HashMap<>();

    public MetricToolRegistrar(McpStatelessSyncServer server, PrometheusClient client,
                               PrometheusConfig config, McpJsonMapper jsonMapper) {
        this.server = server;
        this.client = client;
        this.config = config;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        try {
            sync();
        } catch (Exception e) {
            LOG.error("Initial metric-tool registration failed (will work once Prometheus is reachable; "
                    + "call the refresh_metric_tools tool to retry): {}", e.toString());
        }
    }

    /** (Re)synchronizes the per-metric tools with what Prometheus currently exposes. Thread-safe. */
    public synchronized SyncResult sync() {
        List<String> allNames = fetchMetricNames();
        Map<String, MetricMeta> meta = fetchMetadata();

        Pattern include = Pattern.compile(blankToAny(config.getMetrics().getInclude()));
        String excludeRegex = config.getMetrics().getExclude();
        Pattern exclude = (excludeRegex == null || excludeRegex.isBlank()) ? null : Pattern.compile(excludeRegex);

        List<String> selected = new ArrayList<>();
        for (String name : allNames) {
            if (!include.matcher(name).matches()) {
                continue;
            }
            if (exclude != null && exclude.matcher(name).matches()) {
                continue;
            }
            selected.add(name);
        }
        selected.sort(String::compareTo);

        int max = config.getMetrics().getMaxTools();
        boolean truncated = selected.size() > max;
        if (truncated) {
            selected = selected.subList(0, max);
        }
        Set<String> desired = new HashSet<>(selected);

        int removed = 0;
        for (String metric : new HashSet<>(registered.keySet())) {
            if (!desired.contains(metric)) {
                String toolName = registered.remove(metric);
                try {
                    server.removeTool(toolName);
                    removed++;
                } catch (Exception e) {
                    LOG.debug("removeTool({}) failed: {}", toolName, e.toString());
                }
            }
        }

        Set<String> usedToolNames = new HashSet<>(registered.values());
        int added = 0;
        for (String metric : selected) {
            if (registered.containsKey(metric)) {
                continue;
            }
            String toolName = sanitize(metric);
            if (usedToolNames.contains(toolName)) {
                LOG.warn("Skipping metric '{}': sanitized tool name '{}' collides with an existing tool", metric, toolName);
                continue;
            }
            try {
                server.addTool(buildSpec(metric, toolName, meta.get(metric)));
                registered.put(metric, toolName);
                usedToolNames.add(toolName);
                added++;
            } catch (Exception e) {
                LOG.warn("Could not register tool for metric '{}': {}", metric, e.toString());
            }
        }

        LOG.info("Metric tools synced: {} active ({} added, {} removed) out of {} metrics discovered{}",
                registered.size(), added, removed, allNames.size(),
                truncated ? " [TRUNCATED to maxTools=" + max + "]" : "");
        return new SyncResult(registered.size(), added, removed, truncated, allNames.size());
    }

    private List<String> fetchMetricNames() {
        LabelValuesResponse resp = client.labelNames();
        return (resp == null || resp.data() == null) ? List.of() : resp.data();
    }

    private Map<String, MetricMeta> fetchMetadata() {
        try {
            MetadataResponse resp = client.metadata();
            if (resp == null || resp.data() == null) {
                return Map.of();
            }
            Map<String, MetricMeta> flat = new HashMap<>();
            resp.data().forEach((name, entries) -> {
                if (entries != null && !entries.isEmpty()) {
                    flat.put(name, entries.get(0));
                }
            });
            return flat;
        } catch (Exception e) {
            LOG.debug("Could not fetch metric metadata: {}", e.toString());
            return Map.of();
        }
    }

    private McpStatelessServerFeatures.SyncToolSpecification buildSpec(String metric, String toolName, MetricMeta mm) {
        String type = (mm != null && mm.type() != null && !mm.type().isBlank()) ? mm.type() : "unknown";
        String help = (mm != null && mm.help() != null) ? mm.help() : "";

        String description = ("Prometheus metric '" + metric + "' (type: " + type + "). " + help).trim()
                + " Optional args: 'matchers' (label selector body without braces, e.g. job=\"load-generator\",area=\"heap\"); "
                + "'rate_window' (e.g. 1m, 5m -> wraps in rate(metric{...}[window]), use for counters); "
                + "'start'+'end'+'step' for a range query; 'time' for instant evaluation. "
                + "For aggregations/quantiles/joins use the promql_query tool instead.";

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(toolName)
                .description(description)
                .inputSchema(jsonMapper, INPUT_SCHEMA_JSON)
                .build();

        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((ctx, req) -> handle(metric, req))
                .build();
    }

    private McpSchema.CallToolResult handle(String metric, McpSchema.CallToolRequest req) {
        Map<String, Object> args = req.arguments() == null ? Map.of() : req.arguments();
        String matchers = str(args.get("matchers"));
        String rateWindow = str(args.get("rate_window"));
        String time = str(args.get("time"));
        String start = str(args.get("start"));
        String end = str(args.get("end"));
        String step = str(args.get("step"));

        String selector = metric + (notBlank(matchers) ? "{" + matchers + "}" : "");
        String promql = notBlank(rateWindow) ? "rate(" + selector + "[" + rateWindow + "])" : selector;

        try {
            String body;
            if (notBlank(start) && notBlank(end) && notBlank(step)) {
                body = client.queryRange(promql, start, end, step);
            } else {
                body = client.query(promql, notBlank(time) ? time : null);
            }
            return McpSchema.CallToolResult.builder().addTextContent(body).build();
        } catch (HttpClientResponseException e) {
            String body = e.getResponse().getBody(String.class).orElse(e.getMessage());
            return McpSchema.CallToolResult.builder().isError(true)
                    .addTextContent("Prometheus error for query '" + promql + "': " + body).build();
        } catch (Exception e) {
            return McpSchema.CallToolResult.builder().isError(true)
                    .addTextContent("Error querying '" + promql + "': " + e.getMessage()).build();
        }
    }

    private static String sanitize(String metric) {
        String s = metric.replaceAll("[^a-zA-Z0-9_-]", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static String blankToAny(String regex) {
        return (regex == null || regex.isBlank()) ? ".*" : regex;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Summary of a sync run. */
    public record SyncResult(int active, int added, int removed, boolean truncated, int totalDiscovered) {
    }
}
