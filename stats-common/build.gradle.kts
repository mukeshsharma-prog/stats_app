// stats-common — shared library. NOT a deployable; no Spring Boot plugin, no image.
//
// Holds exactly five things (Senior task SA):
//   1. the envelope model — uid, tenant, maintenant, clsbox, type, ts, data, status
//      (clsbox, NOT gateway: `gateway` is the SRS name no production record has ever
//      used, and ADR-007 §1 makes the wire name the name everywhere — wire, schema,
//      Java, column, API. `gateway` stays a word for the physical device, in prose.)
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

// The architecture guards in src/test/.../architecture read the OTHER modules' main
// sources: StatTypeFanOutTest (no code special-cases one stat type, STA-22/trap 15),
// VersionIsNotADiscriminatorTest (trap 20, DL-105), WriterLogsCarryNoThrowableTest
// (guard P2, T-066), and StructuredLoggingContractTest (T-005). Gradle supplies the
// repository root rather than the tests guessing it from a working directory.
tasks.test {
    systemProperty("stats.repositoryRoot", rootProject.projectDir.absolutePath)

    // ...and those sources are declared as INPUTS, which is what makes the guards real.
    // Gradle has no other way to know this task reads them, so without this block an
    // edit to stats-writer leaves :stats-common:test UP-TO-DATE and every guard above
    // silently does not run — a gate that passes because it never executed. Verified by
    // reintroducing the T-066 defect: UP-TO-DATE and green before this, red after.
    inputs.files(
        listOf("stats-common", "stats-testsupport", "stats-input", "stats-writer", "stats-api")
            .map { rootProject.file("$it/src/main/java") }
            .filter { it.isDirectory }
    )
        .withPropertyName("scannedModuleMainSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .ignoreEmptyDirectories()

    // ...and the same for main RESOURCES, because PropertiesDeclareEachKeyOnceTest scans
    // every module's application.properties. Without this an edit to a properties file
    // leaves :stats-common:test UP-TO-DATE and that guard silently does not run — the
    // identical trap the block above was written for, one directory across. The duplicate
    // retry keys it now catches were added by editing exactly such a file.
    inputs.files(
        listOf("stats-common", "stats-testsupport", "stats-input", "stats-writer", "stats-api")
            .map { rootProject.file("$it/src/main/resources") }
            .filter { it.isDirectory }
    )
        .withPropertyName("scannedModuleMainResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .ignoreEmptyDirectories()
}
