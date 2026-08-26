// stats-common — shared library. NOT a deployable; no Spring Boot plugin, no image.
//
// Holds exactly five things (Senior task SA):
//   1. the envelope model — uid, tenant, maintenant, gateway, type, ts
//   2. the AMQP topology constants — exchange, per-type queue, retry tiers, DLQ
//   3. the stat-type enum — lorastats, ltestats, sysstats, netstats, omsstats
//   4. RFC 9457 Problem Details, the error catalogue, and safe field errors
//   5. Draft 2020-12 schema validation against the normative schema catalogue
//
// The topology constants are not tidiness. Two modules declaring the same
// RabbitMQ resource with different arguments makes the broker reject the
// mismatched declaration with RESOURCE_LOCKED and the module fails to start.
// Declaring them once here is the fix. (claude/CLAUDE.md §6)
//
// Do NOT let this become a dumping ground: only what is genuinely common to all
// three application modules. No module-specific logic, ever. (claude/DEVELOPMENT.md §2)

plugins {
    `java-library`
}

dependencies {
    // JsonNode is part of the public envelope API, and Jackson converters keep
    // timestamp values as Unix seconds on the wire while exposing Instant in Java.
    // This module still has no Spring context, broker client, or JDBC driver.
    api(libs.jackson.annotations)
    api(libs.jackson.databind)
    api(libs.jakarta.validation.api)

    // Jackson can represent JSON trees but cannot evaluate Draft 2020-12 JSON
    // Schema. Keep NetworkNT private behind our ValidationResult API; Joni gives
    // its pattern validators ECMA-262 semantics for parity with the Python gate.
    implementation(libs.networknt.json.schema.validator)
    implementation(libs.joni)

    // Exercise @Pattern as actual Bean Validation behavior without adding a
    // validation provider to stats-common's runtime API.
    testImplementation(libs.spring.boot.starter.validation)
    // Align Gradle's test worker with the BOM-managed JUnit engine.
    testRuntimeOnly(libs.junit.platform.launcher)
}

// docs/schemas is the single source of truth. Copy it into the generated classpath
// output; never check in a second resource copy that can drift from the Python gate.
tasks.processResources {
    from(rootProject.file("docs/schemas")) {
        include("*.schema.json")
        into("schemas")
    }
}

// Fixture tests consume the normative files directly. Gradle copies them only
// into generated test resources; no second checked-in fixture set can drift.
sourceSets {
    test {
        resources.srcDir(rootProject.file("docs/schemas/fixtures"))
    }
}
