package com.marketplace.jobs;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import test.config.AuthorizationServerFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.CLIENT_SECRET;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;
import static test.config.AuthorizationServerFixture.TOKEN_PATH;

/**
 * W6 / J22 (the unified plan §3, the register's J22 row) — the jobs
 * composite journey on the REAL chain (real PostgreSQL + real Redis +
 * the real Flyway schema + the real login gate's Bearer): the employer's
 * posting (born ACTIVE on the public board) → the board's filters → the
 * seeker's application into the employer's inbox → the one-way decision
 * ladder (NEW→REVIEWED→ACCEPTED, both decisions terminal) → the
 * withdrawal's soft delete (a fresh application can follow) → the
 * employer's close (the board stops returning it, the detail stays
 * honest) — with every honest guard measured on the wire: the own-job
 * 400, the duplicate live application 409, the foreign inbox 404, the
 * closed-job 409, the passed-deadline 409, and the illegal move 400.
 *
 * <p><b>The scope's honest bounds (the register's own note):</b> the
 * interview/offer/hiring legs are OUT of the current scope by the
 * module's own contract — this journey proves the implemented machine
 * (post→apply→decide→withdraw→close) and invents nothing beyond it (the
 * plan's own «لا يتم اختراع آلة حالات موحدة لتلك الأنواع»).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway, no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the test profile defaults to 'simple').
        "spring.cache.type=redis",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        // The login gate's client fixtures (the L23 gate properties).
        "marketplace.security.oauth2.client.client-id=" + CLIENT_ID,
        "marketplace.security.oauth2.client.secret=" + CLIENT_SECRET,
        "marketplace.security.oauth2.client.redirect-uris=" + REDIRECT_URI,
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class JobsBoardJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-jobs-journey-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The full journey: the posting rides the public board under its own
     * filters, the seeker's application lands in the employer's inbox, the
     * decision ladder walks its one-way edges, the withdrawal frees a fresh
     * application, and the close takes the job off the board while the
     * detail stays honest — every guard answering its contract's own code.
     */
    @Test
    void theFullJobsJourneyFromPostingThroughDecisionsToClosure() throws Exception {
        // The employer provisions (the /me projection sync first — the
        // strict provider's measured lesson).
        String employerName = "it-jobs-employer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(employerName, "USER", "PROVIDER");
        GateResult employerGate = loginGate(employerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", employerGate.accessToken()).statusCode())
                .as("the employer's /me provisioning sync").isEqualTo(200);

        // (1) The posting — born ACTIVE, the salary block all-or-nothing,
        // the deadline a week out.
        HttpResponse<String> posting = postJson("/api/v1/jobs", employerGate.accessToken(), """
                {"title":"مطلوب مصمم واجهات بدوام كامل","description":"تصميم واجهات تطبيق المجتمع",
                 "employmentType":"FULL_TIME","workplaceType":"ONSITE","city":"الرياض",
                 "district":"الملقا","salaryMinCents":100000,"salaryMaxCents":200000,
                 "salaryCurrency":"SAR","applicationDeadline":"%s"}"""
                .formatted(Instant.now().plus(Duration.ofDays(7))));
        assertThat(posting.statusCode()).as("posting: %s", body(posting)).isEqualTo(201);
        JsonNode jobView = objectMapper.readTree(posting.body());
        UUID jobId = UUID.fromString(jobView.path("id").asString());
        assertThat(jobView.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(jobView.path("salaryCurrency").asString()).isEqualTo("SAR");

        // (2) The public board carries it under its own filters (the city
        // and the employment type).
        HttpResponse<String> board = getWithBearer(
                "/api/v1/jobs?city=" + encode("الرياض") + "&employmentType=FULL_TIME",
                employerGate.accessToken());
        assertThat(board.statusCode()).as("board: %s", body(board)).isEqualTo(200);
        boolean carried = false;
        for (JsonNode row : objectMapper.readTree(board.body()).path("content")) {
            if (row.path("id").asString().equals(jobId.toString())) {
                carried = true;
            }
        }
        assertThat(carried).as("the board carries the posting under its filters").isTrue();

        // (3) The seeker applies — the application lands in the inbox.
        String seekerName = "it-jobs-seeker-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(seekerName, "CONSUMER");
        GateResult seekerGate = loginGate(seekerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", seekerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> application = postJson("/api/v1/jobs/" + jobId + "/applications",
                seekerGate.accessToken(), "{\"coverMessage\":\"خبرة خمس سنوات في تصميم الواجهات\"}");
        assertThat(application.statusCode()).as("apply: %s", body(application)).isEqualTo(201);
        UUID applicationId = UUID.fromString(
                objectMapper.readTree(application.body()).path("id").asString());
        assertThat(objectMapper.readTree(application.body()).path("status").asString()).isEqualTo("NEW");

        // (4) The honest guards, each with its contract's own code:
        // the employer's own job answers 400, the duplicate live
        // application 409, and the foreign inbox 404.
        HttpResponse<String> ownJob = postJson("/api/v1/jobs/" + jobId + "/applications",
                employerGate.accessToken(), "{\"coverMessage\":\"own job\"}");
        assertThat(ownJob.statusCode()).as("the own-job guard: %s", body(ownJob)).isEqualTo(400);
        HttpResponse<String> duplicate = postJson("/api/v1/jobs/" + jobId + "/applications",
                seekerGate.accessToken(), "{\"coverMessage\":\"again\"}");
        assertThat(duplicate.statusCode()).as("the duplicate guard: %s", body(duplicate)).isEqualTo(409);
        String strangerName = "it-jobs-stranger-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(strangerName, "CONSUMER");
        GateResult strangerGate = loginGate(strangerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", strangerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> foreignInbox = getWithBearer(
                "/api/v1/jobs/" + jobId + "/applications", strangerGate.accessToken());
        assertThat(foreignInbox.statusCode()).as("the foreign inbox: existence itself is private").isEqualTo(404);

        // (5) The employer's inbox carries the application (NEW).
        HttpResponse<String> inbox = getWithBearer(
                "/api/v1/jobs/" + jobId + "/applications", employerGate.accessToken());
        assertThat(inbox.statusCode()).as("inbox: %s", body(inbox)).isEqualTo(200);
        boolean inboxCarries = false;
        for (JsonNode row : objectMapper.readTree(inbox.body()).path("content")) {
            if (row.path("id").asString().equals(applicationId.toString())) {
                inboxCarries = true;
                assertThat(row.path("status").asString()).isEqualTo("NEW");
            }
        }
        assertThat(inboxCarries).as("the inbox carries the application").isTrue();

        // (6) The decision ladder's one-way edges: NEW→REVIEWED (200),
        // then the illegal REVIEWED→REVIEWED answers 400, then the
        // terminal REVIEWED→ACCEPTED (200).
        HttpResponse<String> reviewed = patchJson("/api/v1/jobs/" + jobId + "/applications/" + applicationId,
                employerGate.accessToken(), "{\"status\":\"REVIEWED\"}");
        assertThat(reviewed.statusCode()).as("move to REVIEWED: %s", body(reviewed)).isEqualTo(200);
        assertThat(objectMapper.readTree(reviewed.body()).path("status").asString()).isEqualTo("REVIEWED");
        HttpResponse<String> illegalMove = patchJson(
                "/api/v1/jobs/" + jobId + "/applications/" + applicationId,
                employerGate.accessToken(), "{\"status\":\"REVIEWED\"}");
        assertThat(illegalMove.statusCode()).as("the one-way ladder: %s", body(illegalMove)).isEqualTo(400);
        HttpResponse<String> accepted = patchJson(
                "/api/v1/jobs/" + jobId + "/applications/" + applicationId,
                employerGate.accessToken(), "{\"status\":\"ACCEPTED\"}");
        assertThat(accepted.statusCode()).as("move to ACCEPTED: %s", body(accepted)).isEqualTo(200);
        assertThat(objectMapper.readTree(accepted.body()).path("status").asString()).isEqualTo("ACCEPTED");

        // The seeker's own applications list carries the accepted one.
        HttpResponse<String> myApplications = getWithBearer(
                "/api/v1/jobs/me/applications?status=ACCEPTED", seekerGate.accessToken());
        assertThat(myApplications.statusCode()).isEqualTo(200);
        boolean mineCarried = false;
        for (JsonNode row : objectMapper.readTree(myApplications.body()).path("content")) {
            if (row.path("id").asString().equals(applicationId.toString())) {
                mineCarried = true;
            }
        }
        assertThat(mineCarried).as("the seeker's applications list carries the accepted one").isTrue();

        // (7) A second seeker's application — the other decision edge
        // (REVIEWED→REJECTED) and the withdrawal path.
        String seeker2Name = "it-jobs-seeker2-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(seeker2Name, "CONSUMER");
        GateResult seeker2Gate = loginGate(seeker2Name, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", seeker2Gate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> application2 = postJson("/api/v1/jobs/" + jobId + "/applications",
                seeker2Gate.accessToken(), "{\"coverMessage\":\"خبرة سنتين\"}");
        assertThat(application2.statusCode()).as("second apply: %s", body(application2)).isEqualTo(201);
        UUID application2Id = UUID.fromString(
                objectMapper.readTree(application2.body()).path("id").asString());
        HttpResponse<String> rejected = patchJson(
                "/api/v1/jobs/" + jobId + "/applications/" + application2Id,
                employerGate.accessToken(), "{\"status\":\"REJECTED\"}");
        assertThat(rejected.statusCode()).as("the direct NEW→REJECTED edge: %s", body(rejected)).isEqualTo(200);
        assertThat(objectMapper.readTree(rejected.body()).path("status").asString()).isEqualTo("REJECTED");

        // (8) The withdrawal's soft delete: the reads stop returning the
        // row and a FRESH application can follow (the re-apply answers 201).
        HttpResponse<String> withdrawal = deleteWithBearer(
                "/api/v1/jobs/" + jobId + "/applications/" + application2Id,
                seeker2Gate.accessToken(), null);
        assertThat(withdrawal.statusCode()).as("withdraw: %s", body(withdrawal)).isEqualTo(204);
        HttpResponse<String> reapply = postJson("/api/v1/jobs/" + jobId + "/applications",
                seeker2Gate.accessToken(), "{\"coverMessage\":\"تقدم مجدد بعد السحب\"}");
        assertThat(reapply.statusCode()).as("the fresh application after the withdrawal: %s", body(reapply))
                .isEqualTo(201);

        // (9) The employer's close — one-way: the board stops returning
        // the job, the detail stays honest (CLOSED), and the late
        // application answers 409.
        HttpResponse<String> closure = patchJson("/api/v1/jobs/" + jobId,
                employerGate.accessToken(), "{\"status\":\"CLOSED\"}");
        assertThat(closure.statusCode()).as("close: %s", body(closure)).isEqualTo(200);
        assertThat(objectMapper.readTree(closure.body()).path("status").asString()).isEqualTo("CLOSED");
        HttpResponse<String> boardAfter = getWithBearer(
                "/api/v1/jobs?city=" + encode("الرياض"), seekerGate.accessToken());
        boolean stillOnBoard = false;
        for (JsonNode row : objectMapper.readTree(boardAfter.body()).path("content")) {
            if (row.path("id").asString().equals(jobId.toString())) {
                stillOnBoard = true;
            }
        }
        assertThat(stillOnBoard).as("the closed job leaves the default board").isFalse();
        HttpResponse<String> detailAfter = getWithBearer("/api/v1/jobs/" + jobId, seekerGate.accessToken());
        assertThat(detailAfter.statusCode()).as("the closed job's detail stays honest").isEqualTo(200);
        assertThat(objectMapper.readTree(detailAfter.body()).path("status").asString()).isEqualTo("CLOSED");
        HttpResponse<String> lateApply = postJson("/api/v1/jobs/" + jobId + "/applications",
                seekerGate.accessToken(), "{\"coverMessage\":\"too late\"}");
        assertThat(lateApply.statusCode()).as("apply after close: %s", body(lateApply)).isEqualTo(409);
        // The second close answers 400 (the one-way law).
        HttpResponse<String> secondClose = patchJson("/api/v1/jobs/" + jobId,
                employerGate.accessToken(), "{\"status\":\"CLOSED\"}");
        assertThat(secondClose.statusCode()).as("the closed-stays-closed law").isEqualTo(400);

        // (10) The employer's own management list carries both statuses
        // (their posts only — every status).
        HttpResponse<String> myJobs = getWithBearer("/api/v1/jobs/me", employerGate.accessToken());
        assertThat(myJobs.statusCode()).isEqualTo(200);
        boolean mine = false;
        for (JsonNode row : objectMapper.readTree(myJobs.body()).path("content")) {
            if (row.path("id").asString().equals(jobId.toString())) {
                mine = true;
            }
        }
        assertThat(mine).as("the employer's management list carries the job").isTrue();
    }

    /**
     * The deadline's own gate: a posting whose deadline already passed
     * answers the seeker's application with the honest 409 — the state is
     * ACTIVE, the deadline alone refuses.
     */
    @Test
    void thePassedDeadlineRefusesTheApplication() throws Exception {
        String employerName = "it-jobs-dl-employer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(employerName, "USER", "PROVIDER");
        GateResult employerGate = loginGate(employerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", employerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> posting = postJson("/api/v1/jobs", employerGate.accessToken(), """
                {"title":"وظيفة منتهية التقديم","description":"اختبار حارس الموعد",
                 "employmentType":"CONTRACT","workplaceType":"REMOTE","city":"جدة",
                 "applicationDeadline":"%s"}"""
                .formatted(Instant.now().minus(Duration.ofHours(1))));
        assertThat(posting.statusCode()).as("posting: %s", body(posting)).isEqualTo(201);
        UUID jobId = UUID.fromString(objectMapper.readTree(posting.body()).path("id").asString());

        String seekerName = "it-jobs-dl-seeker-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(seekerName, "CONSUMER");
        GateResult seekerGate = loginGate(seekerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", seekerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> lateApplication = postJson("/api/v1/jobs/" + jobId + "/applications",
                seekerGate.accessToken(), "{\"coverMessage\":\"بعد الموعد\"}");
        assertThat(lateApplication.statusCode()).as("the passed deadline: %s", body(lateApplication))
                .isEqualTo(409);
    }

    // ------------------------------------------------------------------
    // The login gate machinery (the L23 five-step sequence, house verbatim)
    // ------------------------------------------------------------------

    private void registerUser(String username, String... roles) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles(roles.length == 0 ? new String[]{"USER"} : roles)
                .build());
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Jobs Journey Gate Integration Test Client");
        String state = UUID.randomUUID().toString();
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + state
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode()).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        String authorizationCode = queryParam(redirect, "code");

        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(tokens.path("access_token").asString(), sessionCookie);
    }

    private record GateResult(String accessToken, String sessionCookie) {
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithBearer(String path, String accessToken, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken);
        if (json != null) {
            builder.header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            builder.DELETE();
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", sessionCookie)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    private String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(c -> SESSION_COOKIE.matcher(c))
                .filter(Matcher::find)
                .map(m -> m.group(1) + "=" + m.group(2))
                .findFirst().orElse("");
    }

    private String latestSessionCookie(HttpResponse<String> response, String fallback) {
        String cookie = sessionCookie(response);
        return cookie.isBlank() ? fallback : cookie;
    }

    private String csrfTokenFrom(String html) {
        Matcher direct = CSRF_INPUT.matcher(html);
        if (direct.find()) {
            return direct.group(1);
        }
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(html);
        return reversed.find() ? reversed.group(1) : "";
    }

    private String queryParam(String url, String param) {
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(param) + "=([^&]+)").matcher(url);
        assertThat(matcher.find()).as("%s must be present in %s", param, url).isTrue();
        return matcher.group(1);
    }

    private String randomCodeVerifier() {
        return base64Url(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}
