package com.taskflow;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TaskFlowApiIT {
    private static final String PASSWORD = "Correct horse 42!";
    // This deterministic key is exclusively for tests, never an application credential.
    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(
            "taskflow-test-only-signing-secret-32-bytes".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

    @DynamicPropertySource
    static void configureDatabaseAndJwt(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> TEST_SECRET);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtEncoder jwtEncoder;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE tasks, users RESTART IDENTITY CASCADE");
    }

    @Test
    void registrationNormalizesEmailAndStoresOnlyABcryptHash() throws Exception {
        MvcResult response = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("  Student@Example.com  ", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.email").value("student@example.com"))
                .andExpect(jsonPath("$.user.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        long userId = body(response).path("user").path("id").asLong();
        Map<String, Object> storedUser = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
        String hash = (String) storedUser.get("password_hash");
        assertEquals("student@example.com", storedUser.get("email"));
        assertNotEquals(PASSWORD, hash);
        assertTrue(hash.startsWith("$2"));
        assertTrue(passwordEncoder.matches(PASSWORD, hash));
        assertNotNull(storedUser.get("created_at"));
        assertFalse(response.getResponse().getContentAsString().contains(hash));
        assertFalse(response.getResponse().getContentAsString().contains(PASSWORD));
    }

    @Test
    void duplicateEmailReturnsConflictAndDatabaseEnforcesUniqueness() throws Exception {
        register("student@example.com");
        MvcResult duplicate = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(" STUDENT@EXAMPLE.COM ", PASSWORD)))
                .andExpect(status().isConflict()).andReturn();
        assertError(duplicate, 409, "/api/auth/register");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO users (email, password_hash, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                "student@example.com", "test-only-value"));
        assertEquals(1L, count("users"));
    }

    @Test
    void concurrentRegistrationsCreateExactlyOneAccount() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> registerAfterSignal(start));
            var second = executor.submit(() -> registerAfterSignal(start));
            start.countDown();
            MvcResult firstResponse = first.get(15, TimeUnit.SECONDS);
            MvcResult secondResponse = second.get(15, TimeUnit.SECONDS);
            List<Integer> statuses = List.of(firstResponse.getResponse().getStatus(),
                    secondResponse.getResponse().getStatus());
            assertTrue(statuses.contains(201));
            assertTrue(statuses.contains(409));
            MvcResult conflict = firstResponse.getResponse().getStatus() == 409 ? firstResponse : secondResponse;
            assertError(conflict, 409, "/api/auth/register");
        }
        assertEquals(1L, count("users"));
    }

    @Test
    void loginAcceptsNormalizedEmailAndHidesWhichCredentialWasWrong() throws Exception {
        Session registered = register("student@example.com");
        MvcResult loggedIn = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(" STUDENT@EXAMPLE.COM ", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(registered.id()))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();
        String token = body(loggedIn).path("accessToken").asString();
        mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        MvcResult wrongPassword = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@example.com", "Wrong password 42!")))
                .andExpect(status().isUnauthorized()).andReturn();
        MvcResult missingUser = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("missing@example.com", PASSWORD)))
                .andExpect(status().isUnauthorized()).andReturn();
        assertError(wrongPassword, 401, "/api/auth/login");
        assertEquals(body(wrongPassword), body(missingUser));
        assertEquals(1L, count("users"));
    }

    @Test
    void everyTaskOperationRequiresABearerToken() throws Exception {
        var requests = List.of(get("/api/tasks"), get("/api/tasks/1"),
                post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Task\"}"),
                put("/api/tasks/1").contentType(MediaType.APPLICATION_JSON).content(validUpdate()),
                delete("/api/tasks/1"));
        for (var request : requests) {
            MvcResult response = mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", startsWith("Bearer"))).andReturn();
            assertError(response, 401, response.getRequest().getRequestURI());
        }
        assertEquals(0L, count("tasks"));
    }

    @Test
    void damagedExpiredFutureAndOtherwiseInvalidTokensAreRejected() throws Exception {
        Session user = register("student@example.com");
        Instant now = Instant.now();
        var wrongKey = new SecretKeySpec(
                "different-test-only-signing-key-32-bytes".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtEncoder wrongEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(wrongKey));
        List<String> invalidTokens = List.of(
                "not.a.valid-token",
                sign(jwtEncoder, claims(user.id()).expiresAt(now.minusSeconds(120)).build()),
                sign(jwtEncoder, claims(user.id()).expiresAt(now.plusSeconds(600))
                        .notBefore(now.plusSeconds(300)).build()),
                sign(jwtEncoder, claims(user.id()).build()),
                sign(jwtEncoder, claims(user.id()).expiresAt(now.plusSeconds(600)).issuer("another-app").build()),
                sign(jwtEncoder, claims(user.id()).expiresAt(now.plusSeconds(600)).subject("not-a-user-id").build()),
                sign(wrongEncoder, claims(user.id()).expiresAt(now.plusSeconds(600)).build()));

        for (String token : invalidTokens) {
            MvcResult response = mvc.perform(get("/api/tasks").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", startsWith("Bearer"))).andReturn();
            assertError(response, 401, "/api/tasks");
            assertFalse(response.getResponse().getContentAsString().contains(token));
        }
    }

    @Test
    void taskLifecyclePersistsDefaultsUpdatesAndDeletion() throws Exception {
        Session owner = register("student@example.com");
        JsonNode created = createTask(owner, "{\"title\":\"Study Spring\"}");
        long id = created.path("id").asLong();
        assertEquals("TODO", created.path("status").asString());
        assertEquals("MEDIUM", created.path("priority").asString());
        assertTrue(created.path("description").isNull());
        assertTrue(created.path("dueDate").isNull());
        assertFalse(created.has("owner"));
        assertFalse(created.has("ownerId"));
        Instant.parse(created.path("createdAt").asString());
        Instant.parse(created.path("updatedAt").asString());
        assertEquals(owner.id(), ((Number) taskRow(id).get("owner_id")).longValue());

        mvc.perform(get("/api/tasks/{id}", id).header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Study Spring"))
                .andExpect(jsonPath("$.createdAt").value(created.path("createdAt").asString()))
                .andExpect(jsonPath("$.updatedAt").value(created.path("updatedAt").asString()));
        // A fixed old timestamp makes the update assertion deterministic without sleeping.
        jdbc.update("UPDATE tasks SET updated_at = '2000-01-01T00:00:00Z' WHERE id = ?", id);
        Object createdAtBefore = taskRow(id).get("created_at");
        mvc.perform(put("/api/tasks/{id}", id).header("Authorization", owner.authorization())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"title":"Finish Spring chapter","description":"Practice repositories",
                                 "status":"DONE","priority":"HIGH","dueDate":"2000-01-01"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Finish Spring chapter"))
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.description").value("Practice repositories"))
                .andExpect(jsonPath("$.dueDate").value("2000-01-01"));
        Map<String, Object> updated = taskRow(id);
        assertEquals("Finish Spring chapter", updated.get("title"));
        assertEquals("DONE", updated.get("status"));
        assertEquals("HIGH", updated.get("priority"));
        assertEquals("Practice repositories", updated.get("description"));
        assertEquals("2000-01-01", updated.get("due_date").toString());
        assertEquals(createdAtBefore, updated.get("created_at"));
        assertTrue(jdbc.queryForObject("SELECT updated_at > '2000-01-01T00:00:00Z' FROM tasks WHERE id = ?",
                Boolean.class, id));

        mvc.perform(delete("/api/tasks/{id}", id).header("Authorization", owner.authorization()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertEquals(0L, count("tasks"));
        MvcResult missing = mvc.perform(get("/api/tasks/{id}", id).header("Authorization", owner.authorization()))
                .andExpect(status().isNotFound()).andReturn();
        assertError(missing, 404, "/api/tasks/" + id);
    }

    @Test
    void explicitNullCreateOptionsUseDefaultsAndPastDatesAreAccepted() throws Exception {
        Session owner = register("student@example.com");
        JsonNode task = createTask(owner, """
                {"title":"Overdue task","status":null,"priority":null,"dueDate":"2000-01-01"}
                """);
        assertEquals("TODO", task.path("status").asString());
        assertEquals("MEDIUM", task.path("priority").asString());
        assertEquals("2000-01-01", task.path("dueDate").asString());
        assertEquals("2000-01-01", taskRow(task.path("id").asLong()).get("due_date").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"Replacement\",\"status\":\"TODO\",\"priority\":\"LOW\"}",
            "{\"title\":\"Replacement\",\"status\":\"TODO\",\"priority\":\"LOW\",\"description\":null,\"dueDate\":null}"
    })
    void fullUpdateClearsOmittedOrNullOptionalFields(String replacement) throws Exception {
        Session owner = register("student@example.com");
        long id = createTask(owner, """
                {"title":"Original","description":"Remove me","dueDate":"2030-01-01"}
                """).path("id").asLong();
        mvc.perform(put("/api/tasks/{id}", id).header("Authorization", owner.authorization())
                        .contentType(MediaType.APPLICATION_JSON).content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").isEmpty()).andExpect(jsonPath("$.dueDate").isEmpty());
        Map<String, Object> stored = taskRow(id);
        assertEquals(null, stored.get("description"));
        assertEquals(null, stored.get("due_date"));
        assertEquals("LOW", stored.get("priority"));
    }

    @ParameterizedTest
    @MethodSource("invalidTaskFields")
    void invalidTaskFieldsReturnValidationErrorsWithoutWrites(String title, String description, String field)
            throws Exception {
        Session owner = register("student@example.com");
        MvcResult response = mvc.perform(post("/api/tasks").header("Authorization", owner.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("title", title, "description", description))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors." + field).isNotEmpty())
                .andReturn();
        assertError(response, 400, "/api/tasks");
        assertEquals(0L, count("tasks"));
    }

    static Stream<Arguments> invalidTaskFields() {
        return Stream.of(Arguments.of("", "", "title"), Arguments.of("   ", "", "title"),
                Arguments.of("x".repeat(121), "", "title"),
                Arguments.of("Valid title", "x".repeat(2001), "description"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{", "{}", "null", "[]", "{\"title\":null}",
            "{\"title\":\"Task\",\"status\":\"UNKNOWN\"}",
            "{\"title\":\"Task\",\"priority\":\"urgent\"}",
            "{\"title\":\"Task\",\"status\":1}",
            "{\"title\":123}", "{\"title\":\"Task\",\"dueDate\":\"yesterday\"}",
            "{\"title\":\"Task\",\"ownerId\":999}",
            "{\"title\":\"Task\",\"createdAt\":\"2000-01-01T00:00:00Z\"}"
    })
    void malformedAndUnknownTaskInputReturns400WithoutWrites(String json) throws Exception {
        Session owner = register("student@example.com");
        MvcResult response = mvc.perform(post("/api/tasks").header("Authorization", owner.authorization())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andReturn();
        assertError(response, 400, "/api/tasks");
        assertEquals(0L, count("tasks"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"TODO\",\"priority\":\"LOW\"}",
            "{\"title\":\"Task\",\"priority\":\"LOW\"}",
            "{\"title\":\"Task\",\"status\":\"TODO\"}",
            "{\"title\":\"Task\",\"status\":null,\"priority\":null}"
    })
    void fullUpdateRequiresTitleStatusAndPriority(String json) throws Exception {
        Session owner = register("student@example.com");
        long id = createTask(owner, "{\"title\":\"Original\"}").path("id").asLong();
        Map<String, Object> before = taskRow(id);
        MvcResult response = mvc.perform(put("/api/tasks/{id}", id).header("Authorization", owner.authorization())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andReturn();
        assertError(response, 400, "/api/tasks/" + id);
        assertFalse(body(response).path("fieldErrors").isEmpty());
        assertEquals(before, taskRow(id));
    }

    @Test
    void foreignAndMissingTasksReturnTheSame404AndCannotBeModifiedOrDeleted() throws Exception {
        Session owner = register("owner@example.com");
        Session attacker = register("attacker@example.com");
        long id = createTask(owner, """
                {"title":"Private task","description":"Keep unchanged","status":"IN_PROGRESS",
                 "priority":"HIGH","dueDate":"2030-01-01"}
                """).path("id").asLong();
        Map<String, Object> before = taskRow(id);
        for (long requestedId : List.of(id, id + 1000)) {
            var requests = List.of(get("/api/tasks/{id}", requestedId),
                    put("/api/tasks/{id}", requestedId).contentType(MediaType.APPLICATION_JSON).content(validUpdate()),
                    delete("/api/tasks/{id}", requestedId));
            for (var request : requests) {
                MvcResult response = mvc.perform(request.header("Authorization", attacker.authorization()))
                        .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Task not found"))
                        .andReturn();
                assertError(response, 404, "/api/tasks/" + requestedId);
                assertEquals(before, taskRow(id));
                assertEquals(1L, count("tasks"));
            }
        }
        mvc.perform(get("/api/tasks").header("Authorization", attacker.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void emptyListIncludesDefaultPagination() throws Exception {
        Session owner = register("student@example.com");
        mvc.perform(get("/api/tasks").header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void filtersApplyTogetherAndExcludeOtherUsersFromItemsAndCounts() throws Exception {
        Session owner = register("student@example.com");
        Session other = register("other@example.com");
        long matchingId = createTask(owner, "{\"title\":\"Match\",\"status\":\"DONE\",\"priority\":\"HIGH\"}")
                .path("id").asLong();
        createTask(owner, "{\"title\":\"Low done\",\"status\":\"DONE\",\"priority\":\"LOW\"}");
        createTask(owner, "{\"title\":\"High todo\",\"status\":\"TODO\",\"priority\":\"HIGH\"}");
        createTask(other, "{\"title\":\"Hidden match\",\"status\":\"DONE\",\"priority\":\"HIGH\"}");

        mvc.perform(get("/api/tasks").param("status", "DONE").param("priority", "HIGH")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(matchingId))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.totalPages").value(1));
        mvc.perform(get("/api/tasks").param("status", "DONE").header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/tasks").param("priority", "HIGH").header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/tasks").header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void paginationOrdersByCreatedAtThenIdAndSupportsAnEmptyLastPage() throws Exception {
        Session owner = register("student@example.com");
        long first = createTask(owner, "{\"title\":\"First\"}").path("id").asLong();
        long second = createTask(owner, "{\"title\":\"Second\"}").path("id").asLong();
        long third = createTask(owner, "{\"title\":\"Third\"}").path("id").asLong();
        // The first two timestamps tie; the highest ID is not the newest timestamp.
        jdbc.update("UPDATE tasks SET created_at = '2020-01-02T00:00:00Z' WHERE id IN (?, ?)", first, second);
        jdbc.update("UPDATE tasks SET created_at = '2020-01-01T00:00:00Z' WHERE id = ?", third);
        mvc.perform(get("/api/tasks").param("page", "0").param("size", "2")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[1].id").value(first)).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/tasks").param("page", "1").param("size", "2")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(third)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/tasks").param("page", "2").param("size", "2")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/tasks").param("size", "50").header("Authorization", owner.authorization()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
    }

    @ParameterizedTest
    @MethodSource("invalidListParameters")
    void invalidListParametersReturn400(String name, String value) throws Exception {
        Session owner = register("student@example.com");
        MvcResult response = mvc.perform(get("/api/tasks").param(name, value)
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isBadRequest()).andReturn();
        assertError(response, 400, "/api/tasks");
    }

    static Stream<Arguments> invalidListParameters() {
        return Stream.of(Arguments.of("page", "-1"), Arguments.of("page", "abc"),
                Arguments.of("page", "1.5"), Arguments.of("page", "2147483648"), Arguments.of("page", ""),
                Arguments.of("page", " "), Arguments.of("size", "0"), Arguments.of("size", "-1"),
                Arguments.of("size", "51"), Arguments.of("size", "abc"), Arguments.of("size", ""),
                Arguments.of("status", "unknown"), Arguments.of("status", "done"), Arguments.of("status", ""),
                Arguments.of("status", " "), Arguments.of("priority", "URGENT"), Arguments.of("priority", ""));
    }

    @Test
    void paginationRejectsAnUnsupportedHugeOffset() throws Exception {
        Session owner = register("student@example.com");
        MvcResult response = mvc.perform(get("/api/tasks").param("page", "2147483647").param("size", "50")
                        .header("Authorization", owner.authorization()))
                .andExpect(status().isBadRequest()).andReturn();
        assertError(response, 400, "/api/tasks");
    }

    @Test
    void openApiAndSwaggerUiArePublicAndDescribeTaskEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/register'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/tasks'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/tasks/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/tasks/{id}'].put.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/tasks'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Swagger UI")));
    }

    private Session register(String email) throws Exception {
        MvcResult response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn();
        JsonNode result = body(response);
        return new Session(result.path("user").path("id").asLong(), result.path("accessToken").asString());
    }

    private MvcResult registerAfterSignal(CountDownLatch start) throws Exception {
        assertTrue(start.await(10, TimeUnit.SECONDS));
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(credentials("race@example.com", PASSWORD))).andReturn();
    }

    private JsonNode createTask(Session owner, String json) throws Exception {
        return body(mvc.perform(post("/api/tasks").header("Authorization", owner.authorization())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn());
    }

    private String credentials(String email, String password) {
        return mapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private static String validUpdate() {
        return "{\"title\":\"Changed\",\"status\":\"DONE\",\"priority\":\"LOW\"}";
    }

    private JsonNode body(MvcResult response) throws Exception {
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private void assertError(MvcResult response, int expectedStatus, String path) throws Exception {
        JsonNode error = body(response);
        assertEquals(expectedStatus, error.path("status").asInt());
        assertEquals(path, error.path("path").asString());
        assertFalse(error.path("message").asString().isBlank());
        assertTrue(error.path("fieldErrors").isObject());
        assertFalse(error.has("trace"));
        assertFalse(error.has("exception"));
        assertFalse(error.has("passwordHash"));
        assertFalse(response.getResponse().getContentAsString().contains(PASSWORD));
    }

    private Map<String, Object> taskRow(long id) {
        return jdbc.queryForMap("SELECT * FROM tasks WHERE id = ?", id);
    }

    private long count(String table) {
        // Callers supply only the fixed table names in this test class.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static JwtClaimsSet.Builder claims(long userId) {
        return JwtClaimsSet.builder().subject(Long.toString(userId)).issuer("taskflow")
                .issuedAt(Instant.now().minusSeconds(300));
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private record Session(long id, String token) {
        String authorization() {
            return "Bearer " + token;
        }
    }
}
