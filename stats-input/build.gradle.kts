// stats-input — Module 1. HTTP POST from Heimdal Core -> AMQP publish.
//
// Validates the payload against its stat type's schema, then publishes to the
// app_stats_input topic exchange with the stat type as the routing key
//
// STA-32 / rule 2: this module has NO write-capable database dependency, and in
// fact no database dependency at all. If a datasource ever appears in this file,
// something has gone wrong — Module 2 is the only component that touches
// PostgreSQL.
//
// STA-04A / rule 8: 2xx only after publisher confirm; 503 if the broker is
// unavailable; no local buffering — Input is stateless and Heimdal owns retry.

plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":stats-common"))

    // Generates configuration metadata for this module's @ConfigurationProperties
    // classes, so an IDE stops reporting every custom `stats.*` key as unknown. That
    // matters beyond tidiness: a properties file where every warning is false is a
    // file where a TRUE warning goes unread. Anything still flagged after this is a
    // key nothing binds — see additional-spring-configuration-metadata.json for the
    // ones read by name rather than bound (DL-130).
    annotationProcessor(libs.spring.boot.configuration.processor)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.spring.boot.starter.actuator)
    // Actuator registers meters; only a registry implementation exports them.
    // Without this /actuator/prometheus 404s even though application.properties
    // exposes it, and the failure is silent (DL-100).
    runtimeOnly(libs.micrometer.registry.prometheus)

    testImplementation(libs.spring.rabbit.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.rabbitmq)
    // Shared real-infrastructure support. testImplementation only, so nothing here
    // ever reaches Input's runtime classpath (DL-099). T-014/T-015/T-016 are proved
    // against a real broker: publisher confirms, unroutable returns and byte-identical
    // publication are all broker behaviours, and a mocked template asserts our own
    // assumptions back to us.
    testImplementation(project(":stats-testsupport"))
}

// The gate tests run against the NORMATIVE fixtures in docs/schemas/fixtures, the
// same files validate-fixtures.py gates in CI. Gradle puts them on the test
// classpath rather than a second checked-in copy drifting from the Python gate.
sourceSets {
    test {
        resources.srcDir(rootProject.file("docs/schemas/fixtures"))
    }
}
