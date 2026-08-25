// stats-common — shared library. NOT a deployable; no Spring Boot plugin, no image.
//
// Holds exactly three things (Senior task SA):
//   1. the envelope model — uid, tenant, maintenant, gateway, type, ts
//   2. the AMQP topology constants — exchange, per-type queue, retry tiers, DLQ
//   3. the stat-type enum — lorastats, ltestats, sysstats, netstats, omsstats
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
    // Annotations only. This module deliberately has no Spring context, no broker
    // client and no JDBC driver — it is a model, not a runtime.
    api(libs.jackson.annotations)
    api(libs.jakarta.validation.api)
}
