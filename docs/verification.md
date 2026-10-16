# Local verification — 2026-10-23

This records checks actually performed while creating the project. It is not a GitHub Actions result or a claim that the Testcontainers suite passed.

Environment: OpenJDK 21.0.12.1, Maven 3.9.12 through the Wrapper, and native PostgreSQL 18.6. Docker CLI and daemon were unavailable. Java 21 was selected explicitly because the host's default `java` was Java 17. Maven's cache was placed in the writable workspace; these path overrides are environment-specific and are not project requirements.

| Check | Actual result |
| --- | --- |
| `./mvnw --batch-mode validate` | Passed: Maven configuration and Wrapper resolved. |
| `./mvnw --batch-mode test` | Passed: initial 37 unit tests, no failures or skips. |
| `./mvnw --batch-mode package` after validation fixes | Passed: 44 unit tests, no failures or skips; executable JAR produced. |
| `./mvnw --batch-mode clean verify` on final source | Unit tests passed (44); API tests compiled; JAR and JaCoCo report produced. Overall command **failed** at Testcontainers startup because no Docker environment was available. |
| JAR + native PostgreSQL, localhost only | Started successfully; Flyway applied both migrations and Hibernate validated the schema. |
| Supplementary live HTTP/SQL checks | Passed: registration/login, normalization and BCrypt storage, defaults, CRUD, two-user access isolation, unchanged foreign rows, list counts, filters, pagination, invalid input, and consistent errors. |
| Concurrent live registration and OpenAPI contract checks | Passed: simultaneous registrations returned 201 and 409 with one stored user. Success response codes, Bearer requirements, required request fields, enum values and pagination bounds matched the documented API. |
| Swagger UI | Opened in the browser; all seven operations rendered. [Screenshot](swagger-ui.png) is from that actual run. |
| `pom.xml`, Compose and CI YAML syntax; Wrapper shell syntax | Passed local parsing / `sh -n mvnw`. This does not prove a container build. |
| Diff and secret review | `git diff --check` passed. No real `.env`, private keys, access tokens, or runtime signing keys were included in the repository. |

The supplementary HTTP harness ran outside the delivered Maven project against the real packaged app and native PostgreSQL. It also inspected stored rows after denied writes/deletes. This is additional evidence, **not a replacement for Testcontainers**. The reproducible project checks remain `./mvnw test`, `./mvnw verify`, and the [manual checklist](manual-testing.md).

Two failures discovered during development were fixed: a JWT unit test attempted to construct an illegal null subject, and an obsolete Jackson 2 enum configuration prevented initial application startup on Jackson 3. Regression tests now load the actual JSON configuration and reject numeric/boolean strings, numeric enums, and unknown properties. Pagination overflow and PostgreSQL timestamp precision were also addressed.

## Still to verify in a Docker-enabled environment

- Run `./mvnw clean verify`: the actual `TaskFlowApiIT` container setup and every API test must complete successfully.
- Run `docker compose up --build`: verify image build, PostgreSQL healthcheck/dependency ordering, and persistence across restarts.
- Run the GitHub Actions workflow after publishing the repository. No remote was configured, no repository was created online, and no push was performed.

Do not interpret the generated local coverage report as full integration coverage: it reflects the unit tests that actually ran. No tests were configured to skip automatically when Docker is missing.
