package com.zenner.datahub.stats.common.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.common.error.FieldError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationResultTest {

    @Test
    void validFactoryCreatesAnErrorFreeResult() {
        ValidationResult result = ValidationResult.valid("v1");

        assertThat(result.valid()).isTrue();
        assertThat(result.schemaVersion()).isEqualTo("v1");
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void invalidFactoryCopiesItsErrorsDefensively() {
        List<FieldError> mutable = new ArrayList<>();
        mutable.add(FieldError.of("/tenant", "missing"));

        ValidationResult result = ValidationResult.invalid("v1", mutable);
        mutable.clear();

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThatThrownBy(() -> result.errors().add(FieldError.of("/gateway", "missing")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void enforcesValidAndInvalidResultInvariants() {
        assertThatThrownBy(() -> new ValidationResult(
                        true, "v1", List.of(FieldError.of("/tenant", "missing"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a valid result cannot contain field errors");

        assertThatThrownBy(() -> new ValidationResult(false, "v1", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an invalid result requires at least one field error");

        assertThatThrownBy(() -> ValidationResult.valid(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("schemaVersion must not be blank");
    }
}
