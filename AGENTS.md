# TaskFlow contributor notes

- Use Java 21, one Maven module, and Controller → Service → Repository.
- Keep code beginner-readable: constructor injection, small methods, explicit DTO mapping.
- Packages: `auth`, `task`, `config`, `common.error` under `com.taskflow`.
- No Lombok, MapStruct, generic base classes, speculative interfaces, or new infrastructure.
- Never expose entities, passwords, tokens, SQL, or stack traces in API responses/logs.
- Keep every task query scoped to the authenticated owner; foreign and missing IDs both return 404.
- Evolve the schema with Flyway; Hibernate must use `ddl-auto=validate`.
- Add behavior tests with each behavior change. Use real PostgreSQL in API integration tests.
- Run `./mvnw test` (Java 21, no Docker) and `./mvnw verify` (Docker required).
- Start locally after preparing `.env`: `docker compose up --build`.
- Review diffs and check for secrets before committing. Never commit `.env` or real credentials.
- Report actual checks and failures honestly; never invent test results or silently skip integration tests.
- Keep code/commit messages in English and the learning guide in Russian.
