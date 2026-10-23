//stats-writer — Module 2. AMQP consume -> PostgreSQL persist
//
// The ONLY component with database write access, and the owner
// of retention/cleanup.
//
// ACK only after the PostgreSQL transaction commits. Failed
// writes go to the per-type DLQ — never silently discarded.
//
// Confirm the retry/DLQ handoff BEFORE ACKing the original.
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
    // ones read by name rather than bound.
    annotationProcessor(libs.spring.boot.configuration.processor)

    // NOT an API surface. This module serves no application endpoint and never
    // will (every GET lives in stats-api). The servlet container exists so
    // actuator has somewhere to publish from: without it `server.port`,
    // `management.endpoints.web.exposure.include` and
    // `management.endpoint.health.probes.enabled` are all inert, /actuator/prometheus
    // does not exist, and docker-compose's readiness healthcheck on 8082 can never
    // pass. Only actuator is served; no business controller exists, and
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
    // than the write silently vanishing -- never leaves the JVM.
    runtimeOnly(libs.micrometer.registry.prometheus)
    // LorastatsConsumerProperties is validated at startup so invalid concurrency
    // or prefetch values fail before a listener connects to RabbitMQ.
    implementation(libs.spring.boot.starter.validation)

    // Migrations are versioned, with Flyway: plain-SQL migrations suit partition DDL,
    // and there is no internal reference service to mandate anything else.
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.rabbit.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
    // Shared real-infrastructure support. testImplementation only, so nothing here
    // ever reaches the Writer's production/runtime classpath.
    testImplementation(project(":stats-testsupport"))
}

// The payload gate and the dialect normaliser are tested against the NORMATIVE fixtures
// in docs/schemas/fixtures — the same files validate-fixtures.py gates in CI and
// stats-input's gate tests already load. Three of them are unmodified production records
// in three mutually incompatible dialects, and a hand-typed copy of one of those
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
