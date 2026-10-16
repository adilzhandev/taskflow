# Local verification — 2026-10-23

The final `./mvnw clean verify` completed with **BUILD SUCCESS: 44 unit tests and 53 integration tests, with zero failures, errors, or skips**. Integration tests used an actual PostgreSQL container. This records local checks, not a GitHub Actions result.

## Environment

- Host: Ubuntu, OpenJDK 21.0.12.1 and Maven 3.9.12 through the Wrapper. Java 21 was selected explicitly because the host default was Java 17.
- Docker Engine/CLI 29.9.2 in rootless mode, Docker Compose 5.6.1, Buildx 0.38.1, and slirp4netns 1.3.6.
- Docker context `rootless`, socket `unix:///run/user/1000/docker.sock`; the user Docker service is enabled and running.
- PostgreSQL 18.6-alpine in both Compose and Testcontainers. Compose publishes the app on `127.0.0.1:8080` and this installation's database on `127.0.0.1:55432`.

## Checks performed

| Check | Actual result |
| --- | --- |
| `./mvnw --batch-mode clean verify` with Java 21 and the rootless Docker socket | Passed in 59.805 seconds. Surefire: 44 tests; Failsafe: 53 tests. No failures, errors, or skips. |
| Testcontainers API suite | Started PostgreSQL, applied both Flyway migrations, and exercised real authentication, services, repositories, DB constraints, and owner isolation. |
| JaCoCo | Generated `target/site/jacoco/index.html` from this clean run, including unit and integration execution data. |
| `docker compose up --build -d` | Passed. The multi-stage image built and ran all 44 unit tests during packaging. PostgreSQL became healthy before the app started; Flyway and Hibernate schema validation succeeded. |
| Supplementary Compose HTTP/SQL checks | 440 checks passed against port 8080 and the Compose database: authentication, BCrypt storage, CRUD, defaults, filters, pagination, validation, error responses, and two-user isolation including unchanged foreign rows. The app ran as UID 10001. |
| Container recreation and persistence | Created a disposable account/task, ran `docker compose up -d --force-recreate db app`, and confirmed both container IDs changed. All 18 follow-up checks passed: public user fields and the entire task row survived unchanged, the API was ready, and protected routes still required authentication. Only the disposable persistence fixture was then removed. |
| Swagger UI | Opened successfully after recreation; all seven operations rendered. The [screenshot](swagger-ui.png) is from the running Compose app. |
| Diff and secret review | `git diff --check` passed. The generated `.env` is ignored by Git, has mode 0600, and is excluded from the source archive. No runtime credentials or tokens were committed. |

The successful Maven command selected Java 21 with `JAVA_HOME` and set `DOCKER_HOST=unix:///run/user/1000/docker.sock`. It also used `MAVEN_USER_HOME=../../work/maven-home` and `-Dmaven.repo.local=../../work/m2` to keep Maven caches in this workspace. These cache paths are specific to the verification environment; the project requires no custom Maven cache settings. See the [README](../README.md#rootless-docker-on-linux) for the normal rootless command.

The supplementary HTTP/SQL scripts ran from the surrounding workspace and are additional checks. The reproducible project test suite is the Maven suite; the [manual checklist](manual-testing.md) also describes an interactive two-user demonstration. Compose remains running after verification.

## Problems resolved

The first verification attempt failed because Docker was absent. Installing rootless Docker then exposed Ubuntu's AppArmor user-namespace restriction. A profile limited to the installed RootlessKit executable resolved it after the user installed that profile with sudo. AppArmor remains enabled, and neither Ryuk nor Testcontainers checks were disabled.

An existing host PostgreSQL service occupied port 5432. Compose now supports `DB_PORT` (default 5432), and this installation uses 55432. The app still connects to `db:5432` inside Compose. The existing host database was left in place.

Earlier development fixes addressed an invalid JWT test fixture, obsolete Jackson 2 enum configuration on Jackson 3, pagination offset overflow, and PostgreSQL timestamp precision. The final complete test run covers those fixes.

## Remaining external check

Run the GitHub Actions workflow after publishing the repository. No Git remote was configured, no repository was created online, and no push was performed.
