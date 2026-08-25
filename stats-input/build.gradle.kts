// stats-input — Module 1. HTTP POST from Heimdal Core -> AMQP publish.
//
// Validates the payload against its stat type's schema, then publishes to the
// app_stats_input topic exchange with the stat type as the routing key.
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

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.amqp)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.spring.rabbit.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.rabbitmq)
}
