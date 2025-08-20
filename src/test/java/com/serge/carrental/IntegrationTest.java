package com.serge.carrental;

import com.serge.carrental.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.serge.carrental.domain.UserAccount;
import com.serge.carrental.repo.UserAccountRepository;
import com.serge.carrental.repo.VerificationTokenRepository;
import com.serge.carrental.domain.VerificationToken;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.web.client.RestTemplate;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(TestSecurityConfig.class)
public class IntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("car_rental")
            .withUsername("nopass")
            .withPassword("nopass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    static GenericContainer<?> mailhog = new GenericContainer<>("mailhog/mailhog:latest")
            .withExposedPorts(1025, 8025);

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {


        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("spring.mail.host", mailhog::getHost);
        registry.add("spring.mail.port", () -> mailhog.getMappedPort(1025).toString());
        registry.add("S3_ENDPOINT", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("S3_ACCESS_KEY", () -> minio.getUserName());
        registry.add("S3_SECRET_KEY", () -> minio.getPassword());
        registry.add("S3_BUCKET", () -> "car-rental");
        registry.add("AUTH_SERVER_ISSUER", () -> "http://localhost");
    }

    @BeforeAll
    static void startContainers() {
        startContainersIfNeeded();
        waitForReadiness();
    }

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    UserAccountRepository users;
    @Autowired
    VerificationTokenRepository tokens;
    @Autowired
    ObjectMapper om;

    @MockBean
    StorageService storageService;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    // ----- Pretty, visible logging helpers -------------------------------------------------
    private static final AtomicInteger STEP = new AtomicInteger(0);
    private static void logStep(String message) {
        System.out.println("\n>>> [STEP " + STEP.incrementAndGet() + "] " + message + " <<<");
    }

    // ---- Helpers to start & wait in @DynamicPropertySource ------------------

    private static void startContainersIfNeeded() {
        System.out.println("Starting containers");
        // With @Container, JUnit will start them, but DynamicPropertySource may run very early.
        if (!postgres.isRunning()) {
            postgres.start();
        }
        if (!redis.isRunning()) {
            redis.start();
        }
        if (!mailhog.isRunning()) {
            mailhog.start();
        }
        if (!minio.isRunning()) {
            minio.start();
        }
    }

    private static void waitForReadiness() {
        // Postgres ready
        System.out.println("Waiting postgres...");
        waitForPostgres(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), Duration.ofSeconds(60));
        // Redis TCP ready
        System.out.println("Waiting redis...");
        waitForTcp(redis.getHost(), redis.getMappedPort(6379), Duration.ofSeconds(60));
        // MailHog ports ready (SMTP + HTTP)
        System.out.println("Waiting mailhog...");
        waitForTcp(mailhog.getHost(), mailhog.getMappedPort(1025), Duration.ofSeconds(60));
        // MinIO ready endpoint
        System.out.println("Waiting MinIO...");
        waitForHttpOk("http://" + minio.getHost() + ":" + minio.getMappedPort(9000) + "/minio/health/ready",
                Duration.ofSeconds(90));
    }

    private static void waitForTcp(String host, int port, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1500);
                return;
            } catch (Exception ignored) {
                sleep(200);
            }
        }
        throw new RuntimeException("Timeout waiting for TCP " + host + ":" + port);
    }

    private static void waitForHttpOk(String url, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code >= 200 && code < 400) {
                    conn.disconnect();
                    return;
                }
            } catch (Exception ignored) {
            }
            sleep(250);
        }
        throw new RuntimeException("Timeout waiting for HTTP " + url);
    }

    private static void waitForPostgres(String jdbcUrl, String user, String pass, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection c = DriverManager.getConnection(jdbcUrl, user, pass)) {
                if (c.isValid(2)) return;
            } catch (Exception ignored) {
                System.out.println(ignored);
            }
            sleep(250);
        }
        throw new RuntimeException("Timeout waiting for Postgres at " + jdbcUrl);
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    @BeforeEach
    void stubStorageService() {
        // Ensure no network calls to S3/MinIO during tests
        Mockito.reset(storageService);
        Mockito.when(storageService.uploadLicense(
                any(byte[].class),
                anyString(),
                anyString())
        ).thenAnswer(inv -> "s3://test-bucket/uploads/" + UUID.randomUUID() + "-mock.jpg");
    }




    // =========================
    // Public endpoints scenarios
    // =========================
    @Test
    void public_endpoints_search_and_type_detail() throws Exception {
        String from = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0).toString();
        String to   = OffsetDateTime.now(ZoneOffset.UTC).plusDays(4).withHour(9).withMinute(0).withSecond(0).withNano(0).toString();

        logStep("Public: /api/cars/types list");
        ResponseEntity<String> types = rest.getForEntity(baseUrl() + "/api/cars/types", String.class);
        assertThat(types.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> tlist = om.readValue(types.getBody(), new TypeReference<>() {});
        assertThat(tlist).isNotEmpty();
        assertThat(tlist.stream().anyMatch(m -> "SEDAN".equals(m.get("id")))).isTrue();

        logStep("Public: /api/cars/search availability");
        ResponseEntity<String> search = rest.getForEntity(baseUrl() + "/api/cars/search?from={f}&to={t}",
                String.class, from, to);
        assertThat(search.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> avail = om.readValue(search.getBody(), new TypeReference<>() {});
        assertThat(avail).isNotEmpty();
        assertThat(tlist.stream().anyMatch(m -> "SUV".equals(m.get("id")))).isTrue();

        logStep("Public: /api/cars/types/{id} details with availability");
        ResponseEntity<String> sedan = rest.getForEntity(baseUrl() + "/api/cars/types/{id}?from={f}&to={t}",
                String.class, "SEDAN", from, to);
        assertThat(sedan.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> s = om.readValue(sedan.getBody(), new TypeReference<>() {});
        assertThat(s.get("typeId")).isEqualTo("SEDAN");
        assertThat(s).containsKeys("available", "days", "estimatedTotal");
    }

    // ======================================
    // Auth flow: register -> verify -> book
    // ======================================
    /*@Test
    void auth_registration_verification_and_booking() throws Exception {
        String email = "reguser+" + UUID.randomUUID() + "@example.com";

        // Register
        logStep("Auth: register user");
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("X-Base-Url", baseUrl()); // so the email contains a link pointing back here
        Map<String, Object> regBody = Map.of(
                "email", email,
                "password", "s3cret!",
                "firstName", "Reg",
                "lastName", "User",
                "phone", "123"
        );
        ResponseEntity<String> reg = rest.postForEntity(baseUrl()+"/api/auth/register",
                new HttpEntity<>(om.writeValueAsString(regBody), h), String.class);
        assertThat(reg.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> regResp = om.readValue(reg.getBody(), new TypeReference<>() {});
        assertThat(regResp.get("status")).isEqualTo("PENDING_VERIFICATION");

        // Grab token from DB and verify
        logStep("Auth: verify user with token");
        Optional<VerificationToken> tokenOpt = tokens.findAll().stream()
                .filter(t -> t.getUser().getEmail().equals(email))
                .findFirst();
        assertThat(tokenOpt).isPresent();
        String token = tokenOpt.get().getToken();
        ResponseEntity<String> verify = rest.getForEntity(baseUrl()+"/api/auth/verify?token={t}", String.class, token);
        assertThat(verify.getStatusCode().is2xxSuccessful()).isTrue();

        // Book a car as verified user
        logStep("Bookings: create by verified user");
        String jwt = JwtTestUtil.minimalJwt(email, "bookings:write");
        String from = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0).toString();
        String to   = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).withHour(10).withMinute(0).withSecond(0).withNano(0).toString();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> body =
                new org.springframework.util.LinkedMultiValueMap<>();
        body.add("typeId", "SEDAN");
        body.add("start", from);
        body.add("end", to);
        byte[] content = "fake image".getBytes(StandardCharsets.UTF_8);
        body.add("driverLicense", new org.springframework.core.io.ByteArrayResource(content){
            @Override public String getFilename(){ return "license.jpg"; }
        });
        ResponseEntity<String> create = rest.exchange(baseUrl()+"/api/bookings",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(create.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> created = om.readValue(create.getBody(), new TypeReference<>() {});
        assertThat(created.get("status")).isEqualTo("TO_CONFIRM");
        assertThat(created).containsKeys("bookingId","typeId","days","estimatedTotal");

        // Ensure confirmation email went out
        logStep("Mail: booking confirmation email delivered via MailHog");
        okhttp3.OkHttpClient http = new okhttp3.OkHttpClient();
        String mailhogUrl = "http://"+mailhog.getHost()+":"+mailhog.getMappedPort(8025)+"/api/v2/messages";
        okhttp3.Request req = new okhttp3.Request.Builder().url(mailhogUrl).build();
        okhttp3.Response resp = http.newCall(req).execute();
        assertThat(resp.isSuccessful()).isTrue();
        String bodyStr = resp.body().string();
        assertThat(bodyStr).contains("Booking received");
    }
*/
    // ===================================
    // Booking validation & auth scenarios
    // ===================================
    @Test
    void booking_validation_errors_and_auth_required() throws Exception {
        // Create unverified and verified users
        UserAccount unverified = new UserAccount();
        unverified.setEmail("eve+"+UUID.randomUUID()+"@example.com");
        unverified.setPasswordHash("{noop}");
        unverified.setEmailVerified(false);
        unverified.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(unverified);

        UserAccount verified = new UserAccount();
        verified.setEmail("bob+"+UUID.randomUUID()+"@example.com");
        verified.setPasswordHash("{noop}");
        verified.setEmailVerified(true);
        verified.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(verified);

        String from = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0).toString();
        String toSame = from; // invalid: end == start

        // 1) Unauthorized request (no token) -> 401
        logStep("Bookings: create without JWT should be 401");
        HttpHeaders hNoAuth = new HttpHeaders();
        hNoAuth.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> bodyNoAuth = new org.springframework.util.LinkedMultiValueMap<>();
        bodyNoAuth.add("typeId", "SEDAN");
        bodyNoAuth.add("start", from);
        bodyNoAuth.add("end", from);
        bodyNoAuth.add("driverLicense", new org.springframework.core.io.ByteArrayResource("img".getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){ return "license.jpg"; }
        });
        ResponseEntity<String> unauthCreate = rest.exchange(baseUrl()+"/api/bookings", HttpMethod.POST,
                new HttpEntity<>(bodyNoAuth, hNoAuth), String.class);
        assertThat(unauthCreate.getStatusCode().value()).isEqualTo(401);

        // 2) Unverified user -> 403 EMAIL_NOT_VERIFIED
        logStep("Bookings: unverified user should receive 403 EMAIL_NOT_VERIFIED");
        HttpHeaders hUnver = new HttpHeaders();
        hUnver.setBearerAuth(JwtTestUtil.minimalJwt(unverified.getEmail(), "bookings:write"));
        hUnver.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> bUnver = new org.springframework.util.LinkedMultiValueMap<>();
        bUnver.add("typeId", "SEDAN");
        bUnver.add("start", from);
        bUnver.add("end", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0).toString());
        bUnver.add("driverLicense", new org.springframework.core.io.ByteArrayResource("img".getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){ return "license.jpg"; }
        });
        ResponseEntity<String> unverCreate = rest.exchange(baseUrl()+"/api/bookings", HttpMethod.POST,
                new HttpEntity<>(bUnver, hUnver), String.class);
        assertThat(unverCreate.getStatusCode().value()).isEqualTo(403);
        assertThat(unverCreate.getBody()).contains("EMAIL_NOT_VERIFIED");

        // 3) Validation error: end must be after start -> 400 VALIDATION_ERROR
        logStep("Bookings: validation end<=start should return 400 VALIDATION_ERROR");
        HttpHeaders hVer = new HttpHeaders();
        hVer.setBearerAuth(JwtTestUtil.minimalJwt(verified.getEmail(), "bookings:write"));
        hVer.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> bBad = new org.springframework.util.LinkedMultiValueMap<>();
        bBad.add("typeId", "SEDAN");
        bBad.add("start", from);
        bBad.add("end", toSame);
        bBad.add("driverLicense", new org.springframework.core.io.ByteArrayResource("img".getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){ return "license.jpg"; }
        });
        ResponseEntity<String> badCreate = rest.exchange(baseUrl()+"/api/bookings", HttpMethod.POST,
                new HttpEntity<>(bBad, hVer), String.class);
        assertThat(badCreate.getStatusCode().value()).isEqualTo(400);
        assertThat(badCreate.getBody()).contains("VALIDATION_ERROR");
    }

    // ==========================================
    // Admin: confirm/reject + list + stats flow
    // ==========================================
   /* @Test
    void admin_confirm_reject_list_and_stats() throws Exception {
        // Prepare a verified user and two bookings (A to confirm, B to reject)
        UserAccount u = new UserAccount();
        u.setEmail("adminflow+"+UUID.randomUUID()+"@example.com");
        u.setPasswordHash("{noop}");
        u.setEmailVerified(true);
        u.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(u);

        String jwtUser = JwtTestUtil.minimalJwt(u.getEmail(), "bookings:write");
        String fromA = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0).toString();
        String toA   = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withHour(9).withMinute(0).withSecond(0).withNano(0).toString();
        String fromB = OffsetDateTime.now(ZoneOffset.UTC).plusDays(4).withHour(9).withMinute(0).withSecond(0).withNano(0).toString();
        String toB   = OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).withHour(9).withMinute(0).withSecond(0).withNano(0).toString();

        HttpHeaders hUser = new HttpHeaders();
        hUser.setBearerAuth(jwtUser);
        hUser.setContentType(MediaType.MULTIPART_FORM_DATA);

        org.springframework.util.LinkedMultiValueMap<String, Object> bodyA = new org.springframework.util.LinkedMultiValueMap<>();
        bodyA.add("typeId", "SEDAN");
        bodyA.add("start", fromA);
        bodyA.add("end", toA);
        bodyA.add("driverLicense", new org.springframework.core.io.ByteArrayResource("img".getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){ return "license.jpg"; }
        });
        ResponseEntity<String> createA = rest.exchange(baseUrl()+"/api/bookings", HttpMethod.POST,
                new HttpEntity<>(bodyA, hUser), String.class);
        assertThat(createA.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> createdA = om.readValue(createA.getBody(), new TypeReference<>() {});
        String bookingIdA = (String) createdA.get("bookingId");

        org.springframework.util.LinkedMultiValueMap<String, Object> bodyB = new org.springframework.util.LinkedMultiValueMap<>();
        bodyB.add("typeId", "SEDAN");
        bodyB.add("start", fromB);
        bodyB.add("end", toB);
        bodyB.add("driverLicense", new org.springframework.core.io.ByteArrayResource("img".getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){ return "license.jpg"; }
        });
        ResponseEntity<String> createB = rest.exchange(baseUrl()+"/api/bookings", HttpMethod.POST,
                new HttpEntity<>(bodyB, hUser), String.class);
        assertThat(createB.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> createdB = om.readValue(createB.getBody(), new TypeReference<>() {});
        String bookingIdB = (String) createdB.get("bookingId");

        // Admin tokens & headers
        String jwtAdmin = JwtTestUtil.minimalJwt("admin@example.com", "admin:write");
        HttpHeaders hAdminJson = new HttpHeaders();
        hAdminJson.setBearerAuth(jwtAdmin);
        hAdminJson.setContentType(MediaType.APPLICATION_JSON);

        // Confirm A
        logStep("Admin: confirm booking A");
        Map<String, Object> confirmBody = Map.of("carRegistrationNumber", "XYZ-1234");
        ResponseEntity<String> confirm = rest.exchange(
                baseUrl()+"/api/admin/bookings/{id}/confirm",
                HttpMethod.POST, new HttpEntity<>(om.writeValueAsString(confirmBody), hAdminJson),
                String.class, bookingIdA);
        assertThat(confirm.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> c = om.readValue(confirm.getBody(), new TypeReference<>() {});
        assertThat(c.get("status")).isIn("BOOKED", "OCCUPIED");
        assertThat(c.get("carRegistrationNumber")).isEqualTo("XYZ-1234");

        // Reject B
        logStep("Admin: reject booking B");
        ResponseEntity<String> reject = rest.exchange(
                baseUrl()+"/api/admin/bookings/{id}/reject",
                HttpMethod.POST, new HttpEntity<>(null, hAdminJson),
                String.class, bookingIdB);
        assertThat(reject.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> r = om.readValue(reject.getBody(), new TypeReference<>() {});
        assertThat(r.get("status")).isEqualTo("REJECTED");

        // List bookings
        logStep("Admin: list bookings in window");
        String listFrom = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0).toString();
        String listTo   = OffsetDateTime.now(ZoneOffset.UTC).plusDays(6).withHour(23).withMinute(59).withSecond(0).withNano(0).toString();
        HttpHeaders hAdmin = new HttpHeaders();
        hAdmin.setBearerAuth(jwtAdmin);
        ResponseEntity<String> list = rest.exchange(
                baseUrl()+"/api/admin/bookings?from={f}&to={t}",
                HttpMethod.GET, new HttpEntity<>(hAdmin), String.class, listFrom, listTo);
        assertThat(list.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> items = om.readValue(list.getBody(), new TypeReference<>() {});
        assertThat(items.stream().anyMatch(m -> bookingIdA.equals(String.valueOf(m.get("bookingId"))))).isTrue();
        assertThat(items.stream().anyMatch(m -> bookingIdB.equals(String.valueOf(m.get("bookingId"))))).isTrue();

        // Stats
        logStep("Admin: utilization stats");
        ResponseEntity<String> stats = rest.exchange(
                baseUrl()+"/api/admin/stats?from={f}&to={t}",
                HttpMethod.GET, new HttpEntity<>(hAdmin), String.class, listFrom, listTo);
        assertThat(stats.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String, Object>> st = om.readValue(stats.getBody(), new TypeReference<>() {});
        assertThat(st).isNotEmpty();

        // User cancels confirmed booking (A) -> CANCELLED
        logStep("Bookings: user cancels booking A");
        HttpHeaders hUserAuth = new HttpHeaders();
        hUserAuth.setBearerAuth(jwtUser);
        ResponseEntity<String> cancel = rest.exchange(
                baseUrl()+"/api/bookings/{id}/cancel",
                HttpMethod.POST, new HttpEntity<>(hUserAuth), String.class, bookingIdA);
        assertThat(cancel.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> cancelResp = om.readValue(cancel.getBody(), new TypeReference<>() {});
        assertThat(cancelResp.get("status")).isEqualTo("CANCELLED");
    }
*/
    // =========================================================================
    // Parameterized end-to-end flow with varied inputs (Cucumber-style table)
    // =========================================================================
    /**
     * Simple scenario descriptor for table-driven tests.
     */
    record Scenario(String label, String typeId, int startPlusDays, int durationDays, int expectedStatus) {}

    static Stream<Arguments> bookingScenarios() {
        return Stream.of(
                // Happy paths across car types & durations
                Arguments.of(new Scenario("SEDAN 2 days exact window", "SEDAN", 2, 2, 201)),
                Arguments.of(new Scenario("SUV 1 day exact window",   "SUV",   3, 1, 201)),
                Arguments.of(new Scenario("VAN 7 days exact window",  "VAN",   4, 7, 201)),
                // Negative case: invalid time range (end == start) -> VALIDATION_ERROR (400)
                Arguments.of(new Scenario("Invalid range (end==start)", "SEDAN", 5, 0, 400))
        );
    }

    @ParameterizedTest(name = "E2E scenario: {0}")
    @MethodSource("bookingScenarios")
    void endToEnd_user_booking_variations(Scenario sc) throws Exception {
        logStep("Scenario start: " + sc.label);

        // 1) Prepare a verified user
        String email = ("paramuser+" + UUID.randomUUID() + "@example.com").toLowerCase();
        UserAccount u = new UserAccount();
        u.setEmail(email);
        u.setPasswordHash("{noop}");
        u.setEmailVerified(true);
        u.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(u);

        // 2) JWT with proper scope
        String jwt = JwtTestUtil.minimalJwt(email, "bookings:write");

        // 3) Compute time window (use exact HH:mm to avoid rounding surprises)
        OffsetDateTime startTs = OffsetDateTime.now(ZoneOffset.UTC)
                .plusDays(sc.startPlusDays)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime endTs = sc.durationDays == 0
                ? startTs // invalid case -> expect 400
                : startTs.plusDays(sc.durationDays);
        String from = startTs.toString();
        String to   = endTs.toString();

        // 4) Public: search and type detail (sanity)
        ResponseEntity<String> search = rest.getForEntity(
                baseUrl()+"/api/cars/search?from={f}&to={t}", String.class, from, to);
        assertThat(search.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> typeDetail = rest.getForEntity(
                baseUrl()+"/api/cars/types/{id}?from={f}&to={t}",
                String.class, sc.typeId, from, to);
        assertThat(typeDetail.getStatusCode().is2xxSuccessful()).isTrue();

        // 5) Try to create a booking
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);

        LinkedMultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();
        body.add("typeId", sc.typeId);
        body.add("start", from);
        body.add("end", to);
        body.add("driverLicense", new org.springframework.core.io.ByteArrayResource("fake image".getBytes(StandardCharsets.UTF_8)){
            @Override public String getFilename(){ return "license.jpg"; }
        });

        ResponseEntity<String> create = rest.exchange(
                baseUrl()+"/api/bookings", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(create.getStatusCode().value()).isEqualTo(sc.expectedStatus);

        if (sc.expectedStatus == 201) {
            Map<String, Object> created = om.readValue(create.getBody(), new TypeReference<>() {});
            String bookingId = (String) created.get("bookingId");
            assertThat(bookingId).isNotNull();
            // Validate expected fields
            assertThat(created.get("typeId")).isEqualTo(sc.typeId);
            assertThat(created.get("status")).isEqualTo("TO_CONFIRM");
            assertThat(created.get("days")).isEqualTo(sc.durationDays);

            // Fetch it back via GET
            HttpHeaders bookingRQHeaders = new HttpHeaders();
            headers.setBearerAuth(jwt);

            ResponseEntity<String> getResp = rest.exchange(
                    baseUrl()+"/api/bookings/{id}",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class,
                    bookingId);

            assertThat(getResp.getStatusCode().is2xxSuccessful()).isTrue();

            // Clean up: cancel to reduce interference between scenarios
            HttpHeaders cancelH = new HttpHeaders();
            cancelH.setBearerAuth(jwt);
            ResponseEntity<String> cancelResp = rest.exchange(
                    baseUrl()+"/api/bookings/{id}/cancel", HttpMethod.POST, new HttpEntity<>(cancelH), String.class, bookingId);
            assertThat(cancelResp.getStatusCode().is2xxSuccessful()).isTrue();
        } else {
            // Negative scenario: body should contain our error code
            assertThat(create.getBody()).contains("VALIDATION_ERROR");
        }

        logStep("Scenario finished: " + sc.label);
    }
}
