package pl.marcinzygmunt.prometheus;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/** A single metadata entry for a metric (from {@code /api/v1/metadata}). */
@Serdeable
public record MetricMeta(@Nullable String type, @Nullable String help, @Nullable String unit) {
}
