// stats-api — Module 3. Read-only REST.
//
// STA-32 / rule 1: GET only. No POST, PUT, PATCH or DELETE, ever — they return
// 405 with no side effect.
//
// STA-35 / rule 3: tenant isolation is enforced server-side on `tenant`, derived
// from the validated token claim. Never from anything the caller supplies.
//
// Q3a: this module gets NO RabbitMQ dependency. The answer said "every module
// declares the exchanges, queues and bindings", which read literally would give a
// read-only HTTP module a broker connection it has no use for. Read as "every
// broker-connected module" and confirm with platform. Until then, no spring-amqp
// here — deliberately.
//
// The datasource is read-only by GRANT, not by convention: this module connects
// as stats_ro so a mistake in code fails loudly instead of silently writing.
// (claude/DEVELOPMENT.md §4)

plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":stats-common"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
}
