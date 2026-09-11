//stats-writer — Module 2. AMQP consume -> PostgreSQL persist
//
// The ONLY component with database write access (STA-32, rule 2), and the owner
// of retention/cleanup (STA-23A).
//
// STA-25A / rule 9: ACK only after the PostgreSQL transaction commits. Failed
// writes go to the per-type DLQ — never silently discarded.
//
// ADR-005 residual: confirm the retry/DLQ handoff BEFORE ACKing the original.
// ACK-then-publish opens a window in which the message is lost.

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

    // NOT an API surface. This module serves no application endpoint and never
    // will (rule 1 puts every GET in stats-api). The servlet container exists so
    // actuator has somewhere to publish from: without it `server.port`,
    // `management.endpoints.web.exposure.include` and
    // `management.endpoint.health.probes.enabled` are all inert, /actuator/prometheus
    // does not exist, and docker-compose's readiness healthcheck on 8082 can never
    // pass. Only actuator is mapped; no controller exists in this module, and
    // ManagementSurfaceTest asserts that stays true.
    implementation(libs.spring.boot.starter.web)

    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.spring.boot.starter.jdbc)
    // LorastatsMessageProcessor deserializes the production envelope with Jackson.
    implementation(libs.spring.boot.starter.json)
    implementation(libs.spring.boot.starter.actuator)
    // Actuator alone registers counters without exporting them. Without a registry
    // implementation, /actuator/prometheus 404s and records_duplicate_suppressed --
    // the only evidence that ON CONFLICT DO NOTHING suppressed a real row rather
    // than the write silently vanishing (STA-27A) -- never leaves the JVM.
    runtimeOnly(libs.micrometer.registry.prometheus)
    // LorastatsConsumerProperties is validated at startup so invalid concurrency
    // or prefetch values fail before a listener connects to RabbitMQ.
    implementation(libs.spring.boot.starter.validation)

    // Migrations are versioned. Flyway — ADR-003 D6, and FINAL since Q1a closed on
    // 18 Aug 2026: there is no internal reference repo to reconcile against, so there
    // is nothing left for the choice to be provisional pending. This file said
    // "PROVISIONAL pending Q1a" while application.properties in the same module said
    // "final since Q1a closed" — corrected 8 Sep 2026.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.rabbit.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
    // Shared real-infrastructure support. testImplementation only, so nothing here
    // ever reaches the Writer's production/runtime classpath (DL-099).
    testImplementation(project(":stats-testsupport"))
}

// The payload gate and the dialect normaliser are tested against the NORMATIVE fixtures
// in docs/schemas/fixtures — the same files validate-fixtures.py gates in CI and
// stats-input's gate tests already load. Three of them are unmodified production records
// in three mutually incompatible dialects (DL-105), and a hand-typed copy of one of those
// stops being a production regression guard the moment it is retyped. Gradle puts them on
// the test classpath rather than a second checked-in copy drifting from the Python gate.
sourceSets {
    test {
        resources.srcDir(rootProject.file("docs/schemas/fixtures"))
    }
}

tasks.register<JavaExec>("redriveDlq") {
    group = "operations"
    description = "Safely redrive a bounded batch from one stat-type DLQ"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.zenner.datahub.stats.writer.redrive.DlqRedriveCommand")
}
