# VS Code Setup from Zero — Stats App

**Audience:** A developer starting with no local project tools installed.  
**Project assumption:** Gradle multi-module Java 21/Spring Boot service with RabbitMQ, PostgreSQL, Keycloak, Docker Compose, and Docker-based integration tests.

> **Platform:** use Java 21. Spring Boot 3.5.16 is pinned in `gradle/libs.versions.toml`; ADR-001 records the baseline.

## 1. Install these machine prerequisites

| Install | Why it is needed | Required? | Notes |
|---|---|---:|---|
| Visual Studio Code | Editor and debugger. | Yes | Use the team-supported VS Code release. |
| JDK 21 | Compile, test, run Java/Spring Boot services. | Yes | Verify Java 21 with `java -version`. |
| Git | Clone, branch, commit, and review code. | Yes | Verify with `git --version`. |
| Docker Desktop (Windows/macOS) or Docker Engine + Compose plugin (Linux) | Local PostgreSQL, RabbitMQ, Keycloak, and integration-test dependencies. | Yes | Verify with `docker version` and `docker compose version`. |
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
code --install-extension ms-ossdata.vscode-pgsql
code --install-extension SonarSource.sonarlint-vscode
code --install-extension usernamehw.errorlens
code --install-extension EditorConfig.EditorConfig
code --install-extension yzhang.markdown-all-in-one
```

## 4. First-time verification

Run these commands before opening the repository. Java should report version 21.

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

1. Read `docs/development/PROJECT_CONTEXT.md`, `docs/development/CODE_START_CHECKLIST.md`, and the relevant requirement/ADR/contract before writing code.
2. Use the Gradle wrapper: `./gradlew build` on macOS/Linux or `gradlew.bat build` on Windows.
3. Start infrastructure only through the checked-in local Compose file: `docker compose -f docker/docker-compose.yml up -d`.
4. Run each service from a separate VS Code terminal or use the checked-in tasks. Do not hard-code local credentials in source files.
5. Use checked-in `.http` requests and test fixtures. Do not use real tenant data, tokens, IMSI, or gateway secrets locally.

## 6. Debugging and test commands

Use the checked-in Gradle tasks. Integration tests run with the normal test suite and require Docker.

```bash
./gradlew test
./gradlew :stats-input:bootRun
./gradlew :stats-writer:bootRun
./gradlew :stats-api:bootRun
```

Use Testcontainers for PostgreSQL and RabbitMQ integration tests rather than relying on shared developer-machine state. Use Docker Compose primarily for end-to-end manual testing and local Keycloak authentication flows.

## 7. Engineering workflow

Use the [development guide](../development/DEVELOPMENT.md),
[implementation rules](../development/IMPLEMENTATION_RULES.md), and
[current plan](../development/PROJECT_STATUS_AND_PLAN.md). Review diffs and run the
checks relevant to a change. No assistant-specific extension or prompt pack is
required to build, test or develop the application.

## References

[VscodeSpring]: https://code.visualstudio.com/docs/java/java-spring-boot "Visual Studio Code — Spring Boot in VS Code"
[VscodeGradle]: https://code.visualstudio.com/docs/java/java-build "Visual Studio Code — Java build tools in VS Code"
[PostgresVSCode]: https://learn.microsoft.com/en-us/azure/postgresql/development/vs-code-extension/postgresql-extension-overview "Microsoft — PostgreSQL extension for Visual Studio Code"
