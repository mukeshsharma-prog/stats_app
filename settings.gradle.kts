// Stats App — module registration.
//
// Four modules, three of which become Docker images (Q2, answered):
//   stats-common  — shared library, not deployable
//   stats-input   — Module 1, HTTP POST -> AMQP
//   stats-writer  — Module 2, AMQP -> PostgreSQL
//   stats-api     — Module 3, read-only REST
//
// Module dependency rule (claude/DEVELOPMENT.md §2): each application module may depend
// on stats-common and NEVER on another application module.

rootProject.name = "stats-app"

include(
    "stats-common",
    "stats-input",
    "stats-writer",
    "stats-api",
)
