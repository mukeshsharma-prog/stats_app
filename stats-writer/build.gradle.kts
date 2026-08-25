// stats-writer — Module 2. AMQP consume -> PostgreSQL persist.
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

    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.actuator)

    // Migrations are versioned. Flyway is PROVISIONAL pending Q1a (ADR-003).
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.rabbit.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.rabbitmq)
}
