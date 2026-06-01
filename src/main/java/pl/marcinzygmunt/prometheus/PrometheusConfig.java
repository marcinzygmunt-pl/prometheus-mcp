package pl.marcinzygmunt.prometheus;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * Configuration for the Prometheus connection and how metrics get turned into MCP tools.
 *
 * <p>The base URL is consumed directly by {@link PrometheusClient} via the
 * {@code ${prometheus.base-url}} placeholder; the {@code metrics.*} settings control which
 * metric names are exposed as dynamic tools.
 */
@ConfigurationProperties("prometheus")
public class PrometheusConfig {

    @NotBlank
    private String baseUrl = "http://prometheus:9090";

    private Metrics metrics = new Metrics();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    @ConfigurationProperties("metrics")
    public static class Metrics {

        /** Regex; only metric names fully matching this become tools. */
        private String include = ".*";

        /** Regex; metric names fully matching this are excluded (applied after include). Blank = nothing excluded. */
        private String exclude = "";

        /** Hard cap on the number of per-metric tools, to avoid flooding the MCP client. */
        private int maxTools = 200;

        public String getInclude() {
            return include;
        }

        public void setInclude(String include) {
            this.include = include;
        }

        public String getExclude() {
            return exclude;
        }

        public void setExclude(String exclude) {
            this.exclude = exclude;
        }

        public int getMaxTools() {
            return maxTools;
        }

        public void setMaxTools(int maxTools) {
            this.maxTools = maxTools;
        }
    }
}
