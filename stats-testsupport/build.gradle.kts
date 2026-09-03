// stats-testsupport — shared Docker-backed integration-test infrastructure.
//
// NOT a deployable and NOT a fourth application module. No Spring Boot plugin, no
// image, nothing here is ever on an application's runtime classpath: the three
// application modules consume it through `testImplementation` only, and the module
// dependency rule is unchanged — an application module still depends on
// stats-common and never on another application module (claude/DEVELOPMENT.md §2).
//
// This started life as stats-common's `java-test-fixtures` variant (DL-092). The
// variant is correct Gradle and builds cleanly, but IDE Gradle importers do not
// resolve it, so every integration test in stats-common and stats-writer showed
// unresolved imports and lost navigation, completion and refactoring — in exactly
// the tests that cover tenant isolation and the loss windows. A plain library
// module is the boring shape every IDE already understands (DL-099).

plugins {
    `java-library`
}

dependencies {
    // `api`, not `implementation`: consumers write @SpringBootTest classes that
    // reference PostgreSQLContainer and RabbitMQContainer directly, so these types
    // are part of this module's surface rather than an internal detail.
    api(platform(libs.spring.boot.bom))
    api(libs.spring.boot.test)
    api(libs.spring.boot.testcontainers)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.rabbitmq)

    // The support classes are proved against the real services they start: the
    // PostgreSQL smoke test runs SQL through the JDBC driver and the RabbitMQ one
    // opens an actual protocol connection.
    testImplementation(libs.postgresql)
    testImplementation(libs.rabbitmq.amqp.client)
    testRuntimeOnly(libs.junit.platform.launcher)
}
