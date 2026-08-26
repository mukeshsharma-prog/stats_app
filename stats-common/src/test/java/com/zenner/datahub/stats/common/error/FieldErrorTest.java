package com.zenner.datahub.stats.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.common.error.FieldError.SafeMessage;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldErrorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void serializesRequiredFieldsAndAReviewedStaticMessage() {
        FieldError error = FieldError.of(
                "/data/plmn", "invalid_type", SafeMessage.INVALID_FIELD_TYPE);

        JsonNode json = OBJECT_MAPPER.valueToTree(error);

        assertThat(json.path("field").asText()).isEqualTo("/data/plmn");
        assertThat(json.path("code").asText()).isEqualTo("invalid_type");
        assertThat(json.path("message").asText()).isEqualTo("Field has an invalid type.");
    }

    @Test
    void omitsTheOptionalMessage() {
        JsonNode json = OBJECT_MAPPER.valueToTree(FieldError.of("/tenant", "missing"));

        assertThat(json.has("message")).isFalse();
    }

    @Test
    void hasNoPublicConstructorAndNoFactoryAcceptingArbitraryMessageText() {
        assertThat(FieldError.class.getConstructors()).isEmpty();

        List<List<Class<?>>> factorySignatures = Arrays.stream(FieldError.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("of"))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .map(Method::getParameterTypes)
                .map(Arrays::asList)
                .toList();

        assertThat(factorySignatures)
                .containsExactlyInAnyOrder(
                        List.of(String.class, String.class),
                        List.of(String.class, String.class, SafeMessage.class));
    }

    @Test
    void requiresNonBlankFieldAndCode() {
        assertThatThrownBy(() -> FieldError.of(" ", "missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("field must not be blank");
        assertThatThrownBy(() -> FieldError.of("/tenant", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("code must not be blank");
    }
}
