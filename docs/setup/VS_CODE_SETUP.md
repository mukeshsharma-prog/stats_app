# VS Code Setup from Zero — Stats App

**Audience:** A developer starting with no local project tools installed.  
**Project assumption:** Gradle multi-module Java 21/Spring Boot service with RabbitMQ, PostgreSQL, Keycloak, Docker Compose, and Claude Code-assisted development.

> **Important:** The final language, JDK, and Spring Boot version are still governed by ADR-001. Install the company-approved JDK when that decision is made. If you need a provisional local setup before then, use an LTS JDK only for non-production scaffolding and replace it once ADR-001 is approved.

## 1. Install these machine prerequisites

| Install | Why it is needed | Required? | Notes |
|---|---|---:|---|
| Visual Studio Code | Editor and debugger. | Yes | Update to the current stable version. Claude Code’s VS Code extension requires VS Code 1.94 or later. [1][ClaudeVSCode] |
| JDK (approved LTS) | Compile, test, run Java/Spring Boot services. | Yes | Use the organization’s approved Java version; verify with `java -version`. |
| Git | Clone, branch, commit, and review code. | Yes | Verify with `git --version`. |
| Docker Desktop (Windows/macOS) or Docker Engine + Compose plugin (Linux) | Local PostgreSQL, RabbitMQ, Keycloak, and integration-test dependencies. | Yes | Verify with `docker version` and `docker compose version`. |
| Claude Code VS Code extension | Plan/review/edit code in VS Code. | Recommended | Requires an eligible Claude subscription or Console account; sign in through the extension. [1][ClaudeVSCode] |
| HTTP test client | Manual testing of local API endpoints. | Recommended | Use REST Client extension below, `curl`, or Postman/Insomnia. |

Do **not** install Gradle globally. The repository must include and use `./gradlew` (or `gradlew.bat` on Windows) so every developer and CI job runs the approved build version.

## 2. Required VS Code extensions

Install the required packs first. VS Code’s official Java documentation recommends the **Extension Pack for Java** and **Spring Boot Extension Pack** for Spring Boot development. The official Gradle tooling is supplied through **Gradle for Java**. [2][VscodeSpring] [3][VscodeGradle]

| Priority | Extension | Marketplace ID | Why it belongs in this project |
|---|---|---|---|
| Required | Extension Pack for Java | `vscjava.vscode-java-pack` | Java language service, debugging, testing, Maven support, and general Java tooling. |
| Required | Spring Boot Extension Pack | `vmware.vscode-boot-dev-pack` | Spring-specific navigation, configuration support, Initializr, and dashboard tooling. |
| Required | Gradle for Java | `vscjava.vscode-gradle` | Imports Gradle projects, runs Gradle tasks, displays dependencies, and supports Gradle build files. |
| Required | Docker | `ms-azuretools.vscode-docker` | Views/controls the local Compose stack and containers. |
| Required | YAML | `redhat.vscode-yaml` | Validates and edits Docker Compose, CI, and deployment YAML. |
| Required | REST Client | `humao.rest-client` | Sends reproducible HTTP requests from checked-in `.http` files. |
| Required for Claude workflow | Claude Code | `anthropic.claude-code` | Provides Claude Code planning, file references, diffs, and approvals inside VS Code. [1][ClaudeVSCode] |
| Recommended | PostgreSQL | `ms-ossdata.vscode-pgsql` | Connect to local PostgreSQL, inspect tables/indexes, and run focused queries; never connect production credentials by default. [4][PostgresVSCode] |
| Recommended | SonarQube for IDE | `SonarSource.sonarlint-vscode` | Detects common code and security issues locally. |
| Recommended | Error Lens | `usernamehw.errorlens` | Makes compiler/linter diagnostics visible in the editor. |
| Recommended | EditorConfig | `EditorConfig.EditorConfig` | Applies shared whitespace/code-style rules if `.editorconfig` is committed. |
| Recommended | Markdown All in One | `yzhang.markdown-all-in-one` | Maintains ADRs, contracts, requirements, and Claude prompts. |
| Optional | GitLens | `eamodio.gitlens` | Adds Git history and blame context; useful but not required. |
| Optional | GitHub Actions | `github.vscode-github-actions` | Install only if the project actually uses GitHub Actions for CI. |

### Do not install separately if the pack already includes it

The Java and Spring packs include overlapping tools. Installing the packs is simpler than hunting individual Java debugger/test/Spring extensions. Only add the standalone Gradle extension because Gradle is central to this project and should be explicit in the workspace recommendations. [2][VscodeSpring] [3][VscodeGradle]

## 3. One-command extension install

After VS Code is installed and its `code` command is available on your PATH, run the following from a terminal. Alternatively, open **Extensions** in VS Code with `Ctrl+Shift+X`, search each Marketplace ID, and install it.

```bash
code --install-extension vscjava.vscode-java-pack
code --install-extension vmware.vscode-boot-dev-pack
code --install-extension vscjava.vscode-gradle
code --install-extension ms-azuretools.vscode-docker
code --install-extension redhat.vscode-yaml
code --install-extension humao.rest-client
code --install-extension anthropic.claude-code
code --install-extension ms-ossdata.vscode-pgsql
code --install-extension SonarSource.sonarlint-vscode
code --install-extension usernamehw.errorlens
code --install-extension EditorConfig.EditorConfig
code --install-extension yzhang.markdown-all-in-one
```

## 4. First-time verification

Run these commands before opening the repository. Replace expected versions only after ADR-001 is approved.

```bash
code --version
git --version
java -version
docker version
docker compose version
```

Then clone/open the repository root, not an individual module:

```bash
git clone <repository-url> stats-app
cd stats-app
code .
```

When VS Code asks whether to install the workspace-recommended extensions, choose **Install**. It will then import the Gradle multi-project through the Gradle for Java extension.

## 5. Initial local workflow

1. Read `claude/CLAUDE.md`, `claude/CODE_START_CHECKLIST.md`, and the relevant requirement/ADR/contract before writing code.
2. Use the Gradle wrapper: `./gradlew build` on macOS/Linux or `gradlew.bat build` on Windows.
3. Start infrastructure only through the checked-in local Compose file: `docker compose -f docker/docker-compose.yml up -d`.
4. Run each service from a separate VS Code terminal or use the checked-in tasks. Do not hard-code local credentials in source files.
5. Use checked-in `.http` requests and test fixtures. Do not use real tenant data, tokens, IMSI, or gateway secrets locally.
6. Keep Claude Code in **Plan** mode while defining a feature. Review its file diffs before accepting them. The extension supports plan review and file `@` references. [1][ClaudeVSCode]

## 6. Debugging and test commands

The future repository should expose the following standard Gradle tasks. Their exact names must be finalized in the build baseline.

```bash
./gradlew test
./gradlew integrationTest
./gradlew :stats-input:bootRun
./gradlew :stats-writer:bootRun
./gradlew :stats-api:bootRun
```

Use Testcontainers for PostgreSQL and RabbitMQ integration tests rather than relying on shared developer-machine state. Use Docker Compose primarily for end-to-end manual testing and local Keycloak authentication flows.

## 7. Claude Code safety workflow

The official Claude Code VS Code extension lets you review plans, approve/reject diffs, use `@` references, and open multiple work sessions. It is the recommended native Claude Code experience in VS Code. [1][ClaudeVSCode]

For this project, always start a coding task with a bounded instruction such as:

```text
Read @claude/CLAUDE.md, @claude/CODE_START_CHECKLIST.md, and @docs/architecture/adr/ADR-002-AMQP-Topology.md.
Do not edit yet. Plan the smallest change required for STA-10 and STA-13, list tests,
and identify any PENDING APPROVAL decisions. Wait for my confirmation.
```

Never grant unattended broad permission to change security configuration, deployment files, database indexes, message topology, or public OpenAPI contracts. Inspect and approve every generated diff.

## References

[ClaudeVSCode]: https://code.claude.com/docs/en/vs-code "Anthropic — Use Claude Code in VS Code"
[VscodeSpring]: https://code.visualstudio.com/docs/java/java-spring-boot "Visual Studio Code — Spring Boot in VS Code"
[VscodeGradle]: https://code.visualstudio.com/docs/java/java-build "Visual Studio Code — Java build tools in VS Code"
[PostgresVSCode]: https://learn.microsoft.com/en-us/azure/postgresql/development/vs-code-extension/postgresql-extension-overview "Microsoft — PostgreSQL extension for Visual Studio Code"

[1][ClaudeVSCode] [2][VscodeSpring] [3][VscodeGradle] [4][PostgresVSCode]
