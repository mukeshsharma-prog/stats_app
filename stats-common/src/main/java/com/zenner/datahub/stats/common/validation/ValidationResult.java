package com.zenner.datahub.stats.common.validation;

import com.zenner.datahub.stats.common.error.FieldError;
import java.util.List;
import java.util.Objects;

/**
 * Result of validating one canonical statistics record against its selected schema.
 *
 * <p>The schema version is retained on both valid and invalid outcomes. A future
 * Writer stores it with accepted records so persisted data remains traceable to
 * the contract that admitted it.</p>
 */
public record ValidationResult(boolean valid, String schemaVersion, List<FieldError> errors) {

    public ValidationResult {
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (valid && !errors.isEmpty()) {
            throw new IllegalArgumentException("a valid result cannot contain field errors");
        }
        if (!valid && errors.isEmpty()) {
            throw new IllegalArgumentException("an invalid result requires at least one field error");
        }
    }

    public static ValidationResult valid(String schemaVersion) {
        return new ValidationResult(true, schemaVersion, List.of());
    }

    public static ValidationResult invalid(String schemaVersion, List<FieldError> errors) {
        return new ValidationResult(false, schemaVersion, errors);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
