package pl.marcinzygmunt.prometheus;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/** Response of {@code GET /api/v1/label/__name__/values}. */
@Serdeable
public record LabelValuesResponse(String status, @Nullable List<String> data) {
}
