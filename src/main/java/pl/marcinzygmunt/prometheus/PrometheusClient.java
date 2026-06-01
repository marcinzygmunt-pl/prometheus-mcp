package pl.marcinzygmunt.prometheus;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

/**
 * Declarative HTTP client for the Prometheus HTTP API.
 *
 * <p>The base URL comes from the {@code prometheus.base-url} property (default
 * {@code http://prometheus:9090}), overridable via the {@code PROMETHEUS_BASE_URL} env var.
 *
 * <p>{@link #query} / {@link #queryRange} return the raw JSON body so it can be handed straight
 * back to the MCP client; non-2xx responses raise {@code HttpClientResponseException}, whose body
 * (Prometheus returns a JSON error envelope) is surfaced by the callers.
 */
@Client("${prometheus.base-url}")
public interface PrometheusClient {

    /** All metric names currently known to Prometheus. */
    @Get("/api/v1/label/__name__/values")
    LabelValuesResponse labelNames();

    /** Type/help/unit metadata aggregated across all scrape targets. */
    @Get("/api/v1/metadata")
    MetadataResponse metadata();

    /** Instant query. */
    @Get("/api/v1/query")
    String query(@QueryValue("query") String query, @Nullable @QueryValue("time") String time);

    /** Range query. */
    @Get("/api/v1/query_range")
    String queryRange(@QueryValue("query") String query,
                      @QueryValue("start") String start,
                      @QueryValue("end") String end,
                      @QueryValue("step") String step);
}
