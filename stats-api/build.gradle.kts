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

    // Generates configuration metadata for this module's @ConfigurationProperties
    // classes, so an IDE stops reporting every custom `stats.*` key as unknown. That
    // matters beyond tidiness: a properties file where every warning is false is a
    // file where a TRUE warning goes unread. Anything still flagged after this is a
    // key nothing binds — see additional-spring-configuration-metadata.json for the
    // ones read by name rather than bound (DL-130).
    annotationProcessor(libs.spring.boot.configuration.processor)

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)
    // Actuator registers meters; only a registry implementation exports them.
    // Without this /actuator/prometheus 404s even though application.properties
    // exposes it, and the failure is silent (DL-100).
    runtimeOnly(libs.micrometer.registry.prometheus)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.security.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    // NOT stats-testsupport: it exports spring-amqp and the RabbitMQ client (`api`), and
    // NoBrokerConnectionTest asserts neither is on this module's classpath in ANY scope
    // (Q3a). The API tests start their own PostgreSQL on the same image instead.
    // The read tests run against the Writer's REAL migrations, applied as the Writer's
    // owner, and read as stats_ro — so the schema the API is tested against cannot drift
    // from the one production has. Flyway is test scope only: this module never migrates
    // (it connects as stats_ro, which cannot), and `spring.flyway.enabled=false` in the
    // test properties keeps Boot from trying.
    testImplementation(libs.flyway.core)
    testRuntimeOnly(libs.flyway.database.postgresql)
}

// T-028, T-045, T-046 (DL-162). The API is tested against two production artifacts rather
// than copies of them: the PostgreSQL role bootstrap (so stats_ro has exactly the grants
// it ships with — DL-135) and the Writer's Flyway migrations (so every column the API
// reads is one the Writer creates). The migrations are passed as a directory rather than
// put on the classpath, because stats-writer's resources also hold its
// application.properties, which must never reach this module's test context.
val writerMigrations = rootProject.file("stats-writer/src/main/resources/db/migration")

sourceSets {
    test {
        resources.srcDir(rootProject.file("docker/postgres-init"))
    }
}

tasks.test {
    inputs.dir(writerMigrations)
    systemProperty("stats.test.writer-migrations", writerMigrations.absolutePath)
}
