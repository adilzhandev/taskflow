# Manual testing checklist

Start the app using the [README](../README.md). Open [Swagger UI](http://localhost:8080/swagger-ui/index.html) or use [requests.http](../requests.http). Use distinct test emails, copy tokens and task IDs from actual responses, and do not save real credentials in the repository. Swagger's **Authorize** input accepts the token value without the `Bearer` prefix.

This is a checklist to run, not a record of completed verification.

## Authentication

- [ ] Register user A: expect `201`, an access token, `tokenType: "Bearer"`, expiry in seconds, and a user without password fields.
- [ ] Register the same email with different case and surrounding spaces: expect `409` and the common error shape (`status`, `message`, `path`, `fieldErrors`).
- [ ] Log in with the correct password: expect `200`; an incorrect password or unknown email must return `401` without identifying which credential was wrong.
- [ ] Call `GET /api/tasks` without a token and with a damaged token: both return JSON `401`.
- [ ] Check the expired-token integration scenario with `./mvnw verify`; it uses a deliberately expired signed token rather than waiting or weakening signature checks.

## Task lifecycle

- [ ] Create a task with only `title`: expect `201`, a `Location` header, `TODO`, `MEDIUM`, null optional fields, and server timestamps. Save its returned ID.
- [ ] Create another task with explicit status, priority, description, and a past `dueDate`: expect `201`; the past date must be accepted.
- [ ] Read the first task by its returned ID: expect `200` and matching fields, without an `owner` object.
- [ ] Replace it using `PUT` with `title`, `status`, and `priority`: expect `200`; omitted `description` and `dueDate` become null, ID and `createdAt` remain unchanged.
- [ ] Delete it: expect `204` with no body. Reading or deleting the same ID afterward returns `404`.

## Validation and list behavior

- [ ] Submit blank or 121-character titles, a 2001-character description, an unknown enum, numeric enum, invalid date, malformed JSON, or an extra field such as `owner` or `ownerId`: expect `400` and no task creation.
- [ ] Omit `status` or `priority` on `PUT`: expect `400`; the existing task must retain its previous values.
- [ ] Register a fresh user: its list is `items: []`, `page: 0`, `size: 10`, `totalElements: 0`, `totalPages: 0`.
- [ ] Create several tasks with different statuses and priorities. Check status-only, priority-only, and combined filters against their returned fields and counts.
- [ ] Fetch `page=0&size=1` and then `page=1&size=1`: expect distinct consecutive items in `createdAt DESC, id DESC` order and consistent totals.
- [ ] Check `page=-1`, `size=0`, `size=51`, `page=abc`, `status=UNKNOWN`, `priority=UNKNOWN`, and blank or whitespace values for all four parameters: expect `400`.
- [ ] Check `page=2147483647&size=50`: expect `400`. The maximum supported pagination offset (`page × size`) is `2147483647`, matching JPA's integer offset limit.
- [ ] Request a valid page beyond the end: expect `200`, an empty `items` array, and the correct filtered totals.

## Isolation between two users

- [ ] Register user B and retain A's and B's tokens separately.
- [ ] As A, create a task with a distinctive title, description, and due date. Save its ID and full response.
- [ ] As B, try `GET`, `PUT`, and `DELETE` using A's task ID. Each must return the same `404` response as a nonexistent task ID, apart from the request path.
- [ ] After each denied mutation, read the task as A and compare **all editable fields and timestamps** with the saved response. The task must remain present and unchanged.
- [ ] As B, list tasks with and without filters. A's task must be absent from both the items and the totals.
- [ ] Repeat a successful read, update, and delete with B's own task to distinguish ownership checks from broken endpoints.

## Persistence and automated checks

- [ ] Restart the Compose services without removing the named volume: previously saved users and tasks should remain.
- [ ] Run `./mvnw test` with Java 21: unit tests run without Docker.
- [ ] Run `./mvnw verify` with Docker available: integration tests inspect real PostgreSQL state, including BCrypt storage, duplicate-email constraints, and unchanged rows after forbidden mutations.
- [ ] Review test reports under `target/surefire-reports` and `target/failsafe-reports`, and coverage under `target/site/jacoco/index.html`. Record failed or unavailable checks explicitly; an unavailable Docker daemon is not a successful integration run.
