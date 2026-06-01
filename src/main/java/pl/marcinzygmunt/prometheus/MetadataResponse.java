package pl.marcinzygmunt.prometheus;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.Map;

/** Response of {@code GET /api/v1/metadata}: metric name -> list of metadata entries. */
@Serdeable
public record MetadataResponse(String status, @Nullable Map<String, List<MetricMeta>> data) {
}
