# prometheus-mcp

MCP (Model Context Protocol) server for Prometheus, built on the **Micronaut MCP** module
(`io.micronaut.mcp:micronaut-mcp-server-java-sdk`) over the official MCP Java SDK.

It speaks the **streamable HTTP** transport on `/mcp` and, at startup, **discovers the metric
names exposed by your Prometheus and registers one MCP tool per metric**. A few helper tools cover
everything per-metric tools can't (arbitrary PromQL, refresh).

Designed to sit next to the `pgo/prometheus` stack (load-generator + Prometheus + node-exporter) on
the shared `monitoring` Docker network and talk to Prometheus at `http://prometheus:9090`.

## About the project — AI as a new dimension of monitoring

Classic monitoring stops at the dashboard: Prometheus scrapes the numbers, Grafana draws the lines,
and a human still has to read the charts, recall the right PromQL, and connect the dots. This project
adds the missing layer — **it puts an AI agent directly in front of the live telemetry.**

The setup is a small, self-contained sandbox:

- a **traffic-generating example machine** — a Java app that hammers itself with synthetic load
  (HTTP calls, a churning in-memory map, real JVM/GC pressure) so there is always something
  realistic to observe;
- **Prometheus + node-exporter** scraping that app and the host (CPU, memory, disk, network, JVM heap,
  GC, custom `loadgen_*` business metrics);
- **this MCP server**, which on startup reads every metric name Prometheus knows and **registers one
  MCP tool per metric** — plus raw-PromQL escape hatches for `rate()`, aggregations and
  `histogram_quantile()`.

Because it speaks the **Model Context Protocol**, any MCP-capable assistant (Claude Code, an IDE
agent, a custom client) can reach the box and ask questions in plain language:

> *"What does CPU load look like over the last 15 minutes?"*
> *"Analyse the heap for me."*
> *"Is the growing `loadgen_map_size` causing a memory leak?"*

The agent picks the right metric tool, composes the PromQL, runs the query, reads the result and
**explains it** — correlating heap, GC live-data and the churning map to tell a leak apart from normal
sawtooth GC behaviour. No dashboard-staring, no remembering query syntax.

The example machine is deliberately exposed this way so MCP clients can connect to it and experiment —
it is a **reference rig for "AI-native observability"**: the same pattern points at any real
Prometheus simply by changing `PROMETHEUS_BASE_URL`.

## Tools

- **`<metric_name>`** — one per discovered metric (e.g. `loadgen_map_size`, `jvm_memory_used_bytes`,
  `process_cpu_usage`, `node_cpu_seconds_total`). Optional args:
  - `matchers` — label selector body without braces, e.g. `area="heap"` or `job="load-generator"`
  - `rate_window` — e.g. `5m` → wraps in `rate(metric{...}[5m])` (use for counters)
  - `start` + `end` + `step` → range query instead of instant
  - `time` → instant evaluation timestamp
- **`promql_query`** — arbitrary instant PromQL (`rate()`, `sum by`, `histogram_quantile()`, …).
- **`promql_query_range`** — arbitrary PromQL range query.
- **`refresh_metric_tools`** — re-scan Prometheus and add/remove per-metric tools.

## Configuration

All overridable via environment variables (see `src/main/resources/application.yml`):

| Env var                 | Default                                                                 | Meaning |
|-------------------------|-------------------------------------------------------------------------|---------|
| `PROMETHEUS_BASE_URL`   | `http://prometheus:9090`                                                | Prometheus API base URL |
| `PROM_METRICS_INCLUDE`  | `loadgen_.*\|results_.*\|jvm_.*\|process_.*\|http_server_.*\|system_.*\|node_.*\|up` | Regex; only fully-matching metric names become tools. Set to `.*` for everything. |
| `PROM_METRICS_EXCLUDE`  | *(empty)*                                                               | Regex; matching names are dropped |
| `PROM_MAX_TOOLS`        | `200`                                                                   | Hard cap on per-metric tools |
| `MICRONAUT_SERVER_PORT` | `8080`                                                                  | In-container HTTP port |

> **Note on tool count.** Prometheus can expose hundreds of metric names. The default allowlist keeps
> the tool set focused on this stack; widening `PROM_METRICS_INCLUDE` to `.*` can register a lot of
> tools and bloat the MCP client's context. Tune with the include/exclude regexes and `PROM_MAX_TOOLS`.

## Run with Docker (alongside the prometheus stack)

```powershell
# 1. Make sure the prometheus stack is up so the 'monitoring' network exists
cd C:\Users\zygmu\Desktop\work\pgo\prometheus\docker
docker compose up -d

# 2. Confirm the external network name (compose prefixes it with the project dir)
docker network ls | findstr monitoring
#   -> e.g. docker_monitoring   (adjust docker-compose.yml `networks.monitoring.name` if different)

# 3. Build + start the MCP server
cd C:\Users\zygmu\Desktop\work\pgo\prometheus-mcp
docker compose up --build -d
```

The server is then reachable on the host at `http://localhost:8765/mcp`.

## Run locally (without Docker)

Requires **JDK 25** (Micronaut 5 baseline). Point it at the host-exposed Prometheus port:

```powershell
$env:PROMETHEUS_BASE_URL = "http://localhost:9090"
.\gradlew run
# serves http://localhost:8080/mcp
```

## Verify

```powershell
# Metrics Prometheus exposes (what this server will turn into tools)
curl http://localhost:9090/api/v1/label/__name__/values

# List the MCP tools (raw JSON-RPC over streamable HTTP)
$body = '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
curl -s -X POST http://localhost:8765/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d $body
```

Easiest interactive check is the **MCP Inspector** (`npx @modelcontextprotocol/inspector`):
choose *Streamable HTTP*, URL `http://localhost:8765/mcp`, then **List Tools** / **Call Tool**.

## Add to Claude Code

```powershell
claude mcp add --transport http prometheus http://localhost:8765/mcp
```

## Build

- `io.micronaut.application` 5.0.0 + `com.gradleup.shadow` (fat JAR) — `./gradlew shadowJar`
- Micronaut 5.0.0, MCP server SDK 1.0.0, Java 25
