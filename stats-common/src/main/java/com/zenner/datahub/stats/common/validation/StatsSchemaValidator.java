package com.zenner.datahub.stats.common.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialects;
import com.networknt.schema.path.PathType;
import com.networknt.schema.regex.JoniRegularExpressionFactory;
import com.networknt.schema.resource.SchemaLoader;
import com.zenner.datahub.stats.common.error.ErrorCode;
import com.zenner.datahub.stats.common.error.FieldError;
import com.zenner.datahub.stats.common.error.FieldError.SafeMessage;
import com.zenner.datahub.stats.common.model.StatType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draft 2020-12 validator for canonical statistics records.
 *
 * <p>The component reads {@code type} before selecting a per-type schema. An
 * attacker-controlled literal is mapped through {@link StatType} and is never
 * used to derive a classpath resource, schema filename, queue, or table name.
 * All six schemas are loaded from the generated classpath registry by their
 * absolute {@code $id}; remote schema fetching is disabled.</p>
 *
 * <p>NetworkNT error messages are deliberately discarded because they may embed
 * offending values. Only JSON Pointers, machine-readable codes, and reviewed
 * {@link SafeMessage} constants cross the public boundary.</p>
 *
 * <p><strong>There are two gates, and they have different consequences</strong>.
 * {@link #validateEnvelope(JsonNode)} is <em>stats-input's</em> gate:
 * a failure is a 400 and nothing is published. {@link #validate(JsonNode)}
 * additionally applies the per-type payload schema and is <em>stats-writer's</em>
 * gate: a failure there is a dead-letter, not a 400, because the record has already
 * been accepted, confirmed, and published. Calling the wrong one moves the failure
 * across that boundary — validating the payload at Input turns a dead-letter into a
 * rejected write that Heimdal will never retry.</p>
 */
public final class StatsSchemaValidator {

    private static final String SCHEMA_RESOURCE_ROOT = "schemas/";
    private static final String ENVELOPE_SCHEMA_FILENAME = "envelope-v1.schema.json";
    private static final Pattern SCHEMA_VERSION_PATTERN = Pattern.compile("-(v[0-9]+)\\.schema\\.json$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String MISSING = "missing";
    private static final String INVALID_TYPE = "invalid_type";
    private static final String INVALID_PARAMETER = "invalid_parameter";

    private final SchemaRegistry schemaRegistry;
    private final LoadedSchema envelopeSchema;
    private final Map<StatType, LoadedSchema> schemasByType;

    public StatsSchemaValidator() {
        this(StatsSchemaValidator.class.getClassLoader());
    }

    StatsSchemaValidator(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");

        Map<String, RawSchema> rawSchemasByFilename = loadRawSchemas(classLoader);
        Map<String, String> sourcesById = new LinkedHashMap<>();
        rawSchemasByFilename.values().forEach(raw -> {
            String previous = sourcesById.put(raw.id(), raw.source());
            if (previous != null) {
                throw new IllegalStateException("Duplicate bundled schema $id: " + raw.id());
            }
        });

        this.schemaRegistry = createOfflineRegistry(sourcesById);
        this.envelopeSchema = loadSchema(rawSchemasByFilename.get(ENVELOPE_SCHEMA_FILENAME));

        EnumMap<StatType, LoadedSchema> selectedSchemas = new EnumMap<>(StatType.class);
        for (StatType type : StatType.values()) {
            // This filename is derived only from the closed enum, never from the
            // record's attacker-controlled type literal.
            String filename = schemaFilename(type);
            selectedSchemas.put(type, loadSchema(rawSchemasByFilename.get(filename)));
        }
        this.schemasByType = Map.copyOf(selectedSchemas);
    }

    /**
     * <strong>stats-input's gate.</strong> Validates one parsed JSON record against
     * the common envelope only. A failure here is a 400 and nothing is published.
     * Malformed JSON belongs to the Input parser layer and never reaches
     * this method.
     *
     * <p>This deliberately does not look at {@code data}. The payload schemas are
     * strict and the dialects production actually sends disagree with each other, so
     * enforcing them at Input would 400 traffic that Heimdal will never retry.
     * That disagreement goes to the Writer's DLQ instead, where it is visible and
     * recoverable.</p>
     */
    public ValidationResult validateEnvelope(JsonNode record) {
        return validateAgainst(record, false);
    }

    /**
     * <strong>stats-writer's gate.</strong> Validates one parsed JSON record against
     * the envelope <em>and</em> the schema for its stat type. A failure here is a
     * dead-letter, not a 400: the record was already accepted, publisher-confirmed,
     * and published, so rejecting it now cannot reach the caller.
     */
    public ValidationResult validate(JsonNode record) {
        return validateAgainst(record, true);
    }

    private ValidationResult validateAgainst(JsonNode record, boolean includePayload) {
        if (record == null || record.isNull() || !record.isObject()) {
            return invalidEnvelope(FieldError.of("/", INVALID_TYPE, SafeMessage.INVALID_FIELD_TYPE));
        }

        JsonNode typeNode = record.get("type");
        if (typeNode == null || typeNode.isNull()) {
            return invalidEnvelope(FieldError.of("/type", MISSING, SafeMessage.MANDATORY_FIELD_ABSENT));
        }
        if (!typeNode.isTextual()) {
            return invalidEnvelope(FieldError.of("/type", INVALID_TYPE, SafeMessage.INVALID_FIELD_TYPE));
        }

        final StatType statType;
        try {
            statType = StatType.fromWireName(typeNode.textValue());
        } catch (IllegalArgumentException unknownType) {
            // Fail before any schema filename, routing key, queue, or table name
            // can be derived from the untrusted literal. An unknown type is an
            // envelope failure and therefore a 400 at Input, never a dead-letter.
            return invalidEnvelope(FieldError.of(
                    "/type",
                    ErrorCode.UNKNOWN_STAT_TYPE.wireCode(),
                    SafeMessage.UNSUPPORTED_STAT_TYPE));
        }

        LoadedSchema selected = includePayload ? schemasByType.get(statType) : envelopeSchema;
        List<Error> validationErrors = selected.schema().validate(record);
        if (validationErrors.isEmpty()) {
            // netstats' schema rejects non-digits and values wider than 19 digits.
            // The remaining signed-BIGINT ceiling is intentionally enforced by
            // the Writer before PostgreSQL conversion, not here.
            return ValidationResult.valid(selected.version());
        }

        List<FieldError> safeErrors = validationErrors.stream()
                .map(StatsSchemaValidator::toFieldError)
                .distinct()
                .sorted(Comparator.comparing(FieldError::field).thenComparing(FieldError::code))
                .toList();
        return ValidationResult.invalid(selected.version(), safeErrors);
    }

    static SchemaRegistry createOfflineRegistry(Map<String, String> sourcesById) {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .pathType(PathType.JSON_POINTER)
                .regularExpressionFactory(JoniRegularExpressionFactory.getInstance())
                .formatAssertionsEnabled(false)
                .failFast(false)
                .typeLoose(false)
                .build();

        SchemaLoader offlineLoader = SchemaLoader.builder()
                .resourceLoaders(loaders -> loaders.resources(Map.copyOf(sourcesById)))
                // NetworkNT 2.x makes remote loading opt-in. Keep the explicit
                // false here so a future refactor cannot silently enable it.
                .fetchRemoteResources(false)
                .build();

        return SchemaRegistry.withDialect(
                Dialects.getDraft202012(),
                builder -> builder.schemaRegistryConfig(config).schemaLoader(offlineLoader));
    }

    SchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    private ValidationResult invalidEnvelope(FieldError error) {
        return ValidationResult.invalid(envelopeSchema.version(), List.of(error));
    }

    private LoadedSchema loadSchema(RawSchema rawSchema) {
        if (rawSchema == null) {
            throw new IllegalStateException("Required bundled schema is missing");
        }
        Schema schema = schemaRegistry.getSchema(SchemaLocation.of(rawSchema.id()));
        // Eagerly resolve every $ref during startup. A missing registry entry
        // therefore fails fast instead of surfacing on the first production record.
        schema.initializeValidators();
        return new LoadedSchema(schema, rawSchema.version());
    }

    private static Map<String, RawSchema> loadRawSchemas(ClassLoader classLoader) {
        List<String> filenames = new ArrayList<>();
        filenames.add(ENVELOPE_SCHEMA_FILENAME);
        for (StatType type : StatType.values()) {
            filenames.add(schemaFilename(type));
        }

        Map<String, RawSchema> schemas = new LinkedHashMap<>();
        for (String filename : filenames) {
            String resource = SCHEMA_RESOURCE_ROOT + filename;
            try (InputStream input = classLoader.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new IllegalStateException("Missing bundled schema resource: " + resource);
                }
                String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                JsonNode document = OBJECT_MAPPER.readTree(source);
                String id = requiredText(document, "$id", filename);
                schemas.put(filename, new RawSchema(id, schemaVersion(id), source));
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read bundled schema: " + resource, exception);
            }
        }
        return Map.copyOf(schemas);
    }

    private static FieldError toFieldError(Error error) {
        String keyword = error.getKeyword();
        String field = instancePointer(error);

        if ("required".equals(keyword)) {
            field = appendPointer(field, error.getProperty());
            return FieldError.of(field, MISSING, SafeMessage.MANDATORY_FIELD_ABSENT);
        }
        if ("type".equals(keyword)) {
            return FieldError.of(field, INVALID_TYPE, SafeMessage.INVALID_FIELD_TYPE);
        }

        // pattern, enum, minimum, maximum, const, minLength, minItems, not,
        // and any future validation keyword are deliberately collapsed into the
        // public invalid_parameter category. Never call error.getMessage(): the
        // library message can include the rejected runtime value.
        return FieldError.of(field, INVALID_PARAMETER, SafeMessage.INVALID_PARAMETER);
    }

    private static String instancePointer(Error error) {
        if (error.getInstanceLocation() == null) {
            return "/";
        }
        String pointer = error.getInstanceLocation().toString();
        return pointer.isEmpty() ? "/" : pointer;
    }

    private static String appendPointer(String pointer, String property) {
        if (property == null || property.isEmpty()) {
            return pointer;
        }
        String escaped = property.replace("~", "~0").replace("/", "~1");
        return "/".equals(pointer) ? "/" + escaped : pointer + "/" + escaped;
    }

    private static String schemaFilename(StatType type) {
        return type.wireName() + "-v1.schema.json";
    }

    private static String schemaVersion(String schemaId) {
        Matcher matcher = SCHEMA_VERSION_PATTERN.matcher(schemaId);
        if (!matcher.find()) {
            throw new IllegalStateException("Schema $id does not contain a version");
        }
        return matcher.group(1);
    }

    private static String requiredText(JsonNode document, String field, String filename) {
        JsonNode value = document.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalStateException("Bundled schema " + filename + " has no valid " + field);
        }
        return value.textValue();
    }

    private record RawSchema(String id, String version, String source) {}

    private record LoadedSchema(Schema schema, String version) {}
}
