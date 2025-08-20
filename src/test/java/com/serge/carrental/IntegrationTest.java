package com.serge.carrental;

import com.serge.carrental.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.serge.carrental.domain.UserAccount;
import com.serge.carrental.repo.UserAccountRepository;
import com.serge.carrental.repo.VerificationTokenRepository;
import com.serge.carrental.domain.VerificationToken;
import io.micrometer.common.util.StringUtils;
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

import java.util.concurrent.*;

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

    // ==========================================================
    // Parallel drain scenario: exhaust availability in threads
    // ==========================================================
    @Test
    void booking_parallel_drain_exhausts_availability() throws Exception {
        String typeId = "SUV";
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end   = start.plusDays(2);
        String from = start.toString();
        String to   = end.toString();

        // Prepare verified user
        String email = ("drainuser+"+UUID.randomUUID()+"@example.com").toLowerCase();
        UserAccount u = new UserAccount();
        u.setEmail(email);
        u.setPasswordHash("{noop}");
        u.setEmailVerified(true);
        u.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(u);
        String jwt = JwtTestUtil.minimalJwt(email, "bookings:write");

        // Check initial availability
        ResponseEntity<String> td = rest.getForEntity(
                baseUrl()+"/api/cars/types/{id}?from={f}&to={t}", String.class, typeId, from, to);
        Map<String,Object> tdJson = om.readValue(td.getBody(), new TypeReference<>(){});
        int initialAvail = (Integer) tdJson.get("available");
        System.out.println("Initial availability for " + typeId + ":" + initialAvail);
        assertThat(initialAvail).isGreaterThan(0);

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, initialAvail));
        java.util.List<Callable<Boolean>> tasks = new java.util.ArrayList<>();

        for (int i = 0; i < initialAvail + 12; i++) {
            final int idx = i;
            tasks.add(() -> {
                HttpHeaders h = new HttpHeaders();
                h.setBearerAuth(jwt);
                h.setContentType(MediaType.MULTIPART_FORM_DATA);
                LinkedMultiValueMap<String,Object> body = new LinkedMultiValueMap<>();
                body.add("typeId", typeId);
                body.add("start", from);
                body.add("end", to);
                body.add("driverLicense", new org.springframework.core.io.ByteArrayResource(("img"+idx).getBytes()){
                    @Override public String getFilename(){ return "l.jpg"; }
                });
                ResponseEntity<String> resp = rest.exchange(
                        baseUrl()+"/api/bookings",
                        HttpMethod.POST,
                        new HttpEntity<>(body, h),
                        String.class);
                Map<String,Object> afterJson = om.readValue(resp.getBody(), new TypeReference<>(){});
                if (resp.getStatusCode().value() == 201){
                    System.out.println("Booked " + afterJson.get("bookingId"));
                } else {
                    System.out.println("Booking rejected");
                }

                return resp.getStatusCode().value() == 201;
            });
        }

        java.util.List<Future<Boolean>> results = pool.invokeAll(tasks);
        pool.shutdown();
        pool.awaitTermination(600, TimeUnit.SECONDS);

        long successes = results.stream().filter(f -> {
            try { return f.get(); } catch (Exception e)
            {
                e.printStackTrace();
                return false;
            }
        }).count();

        assertThat(successes).isEqualTo(initialAvail);

        // After drain, availability should be 0
        ResponseEntity<String> afterTd = rest.getForEntity(
                baseUrl()+"/api/cars/types/{id}?from={f}&to={t}", String.class, typeId, from, to);
        Map<String,Object> afterJson = om.readValue(afterTd.getBody(), new TypeReference<>(){});
        assertThat((Integer) afterJson.get("available")).isEqualTo(0);
    }

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

        // 4) Public: search and type detail (sanity) + JSON validation
        ResponseEntity<String> search = rest.getForEntity(
                baseUrl()+"/api/cars/search?from={f}&to={t}", String.class, from, to);
        assertThat(search.getStatusCode().is2xxSuccessful()).isTrue();
        List<Map<String,Object>> availList = om.readValue(search.getBody(), new TypeReference<>(){});
        assertThat(availList).anyMatch(m -> sc.typeId.equals(m.get("typeId")));

        ResponseEntity<String> typeDetail = rest.getForEntity(
                baseUrl()+"/api/cars/types/{id}?from={f}&to={t}",
                String.class, sc.typeId, from, to);
        assertThat(typeDetail.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String,Object> detailJson = om.readValue(typeDetail.getBody(), new TypeReference<>(){});
        assertThat(detailJson.get("typeId")).isEqualTo(sc.typeId);
        assertThat(detailJson).containsKeys("available","days","estimatedTotal");
        int initialAvail = (Integer) detailJson.get("available");

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

            // After booking: availability should reduce by 1
            ResponseEntity<String> afterTypeDetail = rest.getForEntity(
                    baseUrl()+"/api/cars/types/{id}?from={f}&to={t}",
                    String.class, sc.typeId, from, to);
            Map<String,Object> afterJson = om.readValue(afterTypeDetail.getBody(), new TypeReference<>(){});
            int afterAvail = (Integer) afterJson.get("available");
            assertThat(afterAvail).isEqualTo(initialAvail - 1);

            // Fetch it back via GET and validate JSON
            HttpHeaders getH = new HttpHeaders();
            getH.setBearerAuth(jwt);
            ResponseEntity<String> getResp = rest.exchange(
                    baseUrl()+"/api/bookings/{id}",
                    HttpMethod.GET,
                    new HttpEntity<>(getH),
                    String.class,
                    bookingId);
            assertThat(getResp.getStatusCode().is2xxSuccessful()).isTrue();
            Map<String,Object> getJson = om.readValue(getResp.getBody(), new TypeReference<>(){});
            assertThat(getJson.get("bookingId")).isEqualTo(bookingId);
            assertThat(getJson.get("typeId")).isEqualTo(sc.typeId);
            assertThat(getJson).containsKeys("start","end","days","estimatedTotal","createdAt");

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
