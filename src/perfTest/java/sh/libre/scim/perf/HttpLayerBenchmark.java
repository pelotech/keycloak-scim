package sh.libre.scim.perf;

import com.github.tomakehurst.wiremock.WireMockServer;
import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.resources.complex.Name;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Email;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sh.libre.scim.core.KeepAliveConfigManipulator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Layer-by-layer micro-benchmark to localize the residual ~30 ms/request
 * localhost SCIM HTTP cost.
 *
 * <p>This test deliberately runs <em>without</em> Keycloak or the
 * Testcontainers SSH tunnel — WireMock starts in-process on a loopback port
 * and is hit directly. That way we isolate "what does the HTTP client stack
 * actually cost on this hardware" from the cost of the test rig (container
 * → bridge → SSH tunnel → host hop) that the full integration / perf tests
 * incur via {@code host.testcontainers.internal}.
 *
 * <p>Three paths, all 1000 sequential POSTs to the same loopback endpoint:
 * <ol>
 *   <li><strong>JDK HttpClient</strong> ({@code java.net.http.HttpClient}) —
 *       the JVM's built-in HTTP/1.1 client with default keepalive. Establishes
 *       a JDK-level floor for the platform.</li>
 *   <li><strong>Apache HttpClient + our keepalive config</strong> — the same
 *       knobs {@link KeepAliveConfigManipulator} applies (keepalive on,
 *       maxPerRoute=32). This is what the SCIM SDK uses underneath.</li>
 *   <li><strong>SCIM SDK ScimRequestBuilder.create</strong> — the full
 *       send-a-User code path used by {@code ScimClient.create}: SCIM
 *       resource serialization, JSON marshal, HTTP round-trip, response
 *       deserialize.</li>
 * </ol>
 *
 * <p>Reading the output:
 * <ul>
 *   <li>(A) ≈ a few ms ⇒ TCP loopback is fine; the perf test's 30 ms floor
 *       is environmental (container hop / SSH tunnel).</li>
 *   <li>(B) − (A) ⇒ Apache HttpClient overhead.</li>
 *   <li>(C) − (B) ⇒ SCIM SDK overhead (serialization + resource construction).</li>
 * </ul>
 *
 * <p>Not part of {@code check}; invoke as
 * {@code ./gradlew performanceTest --tests HttpLayerBenchmark}.
 */
public class HttpLayerBenchmark {

    private static final int N = Integer.getInteger("perf.http.iterations", 1000);
    private static final int WARMUP = Integer.getInteger("perf.http.warmup", 100);

    private static WireMockServer wireMock;
    private static String baseUrl;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "placeholder",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(UUID.randomUUID()))));
        baseUrl = "http://127.0.0.1:" + wireMock.port();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void layerByLayerBreakdown() throws Exception {
        System.out.printf(
            "[perf] HttpLayerBenchmark target=%s N=%d warmup=%d%n",
            baseUrl, N, WARMUP);

        long jdkAvgUs = runJdkHttpClient();
        long apacheAvgUs = runApacheHttpClient();
        long sdkAvgUs = runScimSdk();

        System.out.println("[perf] HttpLayerBenchmark summary (avg per request):");
        System.out.printf("  JDK   HttpClient                : %6d µs (%.2f ms)%n",
            jdkAvgUs, jdkAvgUs / 1000.0);
        System.out.printf("  Apache HttpClient + our config : %6d µs (%.2f ms)%n",
            apacheAvgUs, apacheAvgUs / 1000.0);
        System.out.printf("  SCIM SDK ScimRequestBuilder    : %6d µs (%.2f ms)%n",
            sdkAvgUs, sdkAvgUs / 1000.0);
        System.out.printf("  Apache - JDK (Apache overhead) : %6d µs%n",
            apacheAvgUs - jdkAvgUs);
        System.out.printf("  SDK    - Apache (SDK overhead) : %6d µs%n",
            sdkAvgUs - apacheAvgUs);
    }

    private long runJdkHttpClient() throws Exception {
        var client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        String body = "{\"userName\":\"x\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}";
        var requestTemplate = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/Users"))
            .header("Content-Type", "application/scim+json")
            .POST(HttpRequest.BodyPublishers.ofString(body));

        for (int i = 0; i < WARMUP; i++) {
            client.send(requestTemplate.build(), HttpResponse.BodyHandlers.ofString());
        }
        long start = System.nanoTime();
        for (int i = 0; i < N; i++) {
            client.send(requestTemplate.build(), HttpResponse.BodyHandlers.ofString());
        }
        return ((System.nanoTime() - start) / 1000) / N;
    }

    private long runApacheHttpClient() throws Exception {
        // Same knobs KeepAliveConfigManipulator applies in production: honor
        // keepalive headers, default-keepalive duration, raise pool size.
        HttpClientBuilder builder = HttpClients.custom()
            .setConnectionReuseStrategy(DefaultConnectionReuseStrategy.INSTANCE)
            .setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE)
            .setMaxConnPerRoute(32)
            .setMaxConnTotal(64)
            .setDefaultRequestConfig(RequestConfig.custom()
                .setConnectTimeout(5_000)
                .setSocketTimeout(5_000)
                .setConnectionRequestTimeout(5_000)
                .build());

        try (CloseableHttpClient client = builder.build()) {
            String body = "{\"userName\":\"x\",\"schemas\":[\"urn:ietf:params:scim:schemas:core:2.0:User\"]}";

            for (int i = 0; i < WARMUP; i++) {
                doApachePost(client, body);
            }
            long start = System.nanoTime();
            for (int i = 0; i < N; i++) {
                doApachePost(client, body);
            }
            return ((System.nanoTime() - start) / 1000) / N;
        }
    }

    private void doApachePost(CloseableHttpClient client, String body) throws Exception {
        HttpPost req = new HttpPost(baseUrl + "/Users");
        req.setHeader("Content-Type", "application/scim+json");
        req.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        try (CloseableHttpResponse resp = client.execute(req)) {
            HttpEntity entity = resp.getEntity();
            if (entity != null) {
                EntityUtils.consume(entity);
            }
        }
    }

    private long runScimSdk() {
        ScimClientConfig cfg = ScimClientConfig.builder()
            .connectTimeout(5)
            .requestTimeout(5)
            .socketTimeout(5)
            .hostnameVerifier((s, sslSession) -> true)
            .configManipulator(new KeepAliveConfigManipulator())
            .build();

        ScimRequestBuilder builder = new ScimRequestBuilder(baseUrl, cfg);

        // Construct a User once; reuse the resource definition. .setResource
        // mutates per-call but the builder pattern doesn't reuse a User
        // instance across calls (each .create() returns a fresh builder).
        for (int i = 0; i < WARMUP; i++) {
            sendOneScimUser(builder, "warmup-" + i);
        }
        long start = System.nanoTime();
        for (int i = 0; i < N; i++) {
            sendOneScimUser(builder, "u-" + i);
        }
        return ((System.nanoTime() - start) / 1000) / N;
    }

    private void sendOneScimUser(ScimRequestBuilder builder, String userName) {
        User user = User.builder()
            .userName(userName)
            .name(Name.builder().givenName("g").familyName("f").build())
            .emails(List.of(Email.builder().value(userName + "@example.com").build()))
            .build();
        try {
            ServerResponse<User> resp = builder
                .create(User.class, "/Users")
                .setResource(user)
                .sendRequest();
            if (!resp.isSuccess()) {
                throw new IllegalStateException("scim create failed: " + resp.getHttpStatus());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
