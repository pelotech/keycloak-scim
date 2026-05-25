package sh.libre.scim.core;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuthClientCredentialsTokenSourceTest {

    private OAuthConfig cfg;
    private OAuthClientCredentialsTokenSource.TokenMinter minter;

    @BeforeEach
    void setUp() {
        OAuthClientCredentialsTokenSource.resetCacheForTests();
        OAuthClientCredentialsTokenSource.setClockForTests(Clock.fixed(Instant.parse("2026-05-22T00:00:00Z"), ZoneOffset.UTC));
        cfg = new OAuthConfig("https://kc/token", "client", "secret", null);
        minter = mock(OAuthClientCredentialsTokenSource.TokenMinter.class);
        when(minter.mint(cfg)).thenReturn(
            new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.first", 300));
    }

    @Test
    void firstCall_mintsToken() {
        var src = new OAuthClientCredentialsTokenSource("comp-1", cfg, minter);
        assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.first");
        verify(minter, times(1)).mint(cfg);
    }

    @Test
    void secondCallBeforeRefreshAt_returnsCached() {
        var src = new OAuthClientCredentialsTokenSource("comp-1", cfg, minter);
        src.currentAuthorizationHeader();
        src.currentAuthorizationHeader();
        verify(minter, times(1)).mint(cfg);
    }

    @Test
    void secondCallAfterRefreshAt_mintsAgain() {
        Instant t0 = Instant.parse("2026-05-22T00:00:00Z");
        OAuthClientCredentialsTokenSource.setClockForTests(Clock.fixed(t0, ZoneOffset.UTC));
        when(minter.mint(cfg))
            .thenReturn(new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.first", 60))
            .thenReturn(new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.second", 60));

        var src = new OAuthClientCredentialsTokenSource("comp-1", cfg, minter);
        assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.first");

        OAuthClientCredentialsTokenSource.setClockForTests(Clock.fixed(t0.plusSeconds(31), ZoneOffset.UTC));
        assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.second");
        verify(minter, times(2)).mint(cfg);
    }

    @Test
    void concurrentMisses_singleMint() throws Exception {
        var latch = new java.util.concurrent.CountDownLatch(1);
        when(minter.mint(cfg)).thenAnswer(inv -> {
            latch.await();
            return new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.shared", 300);
        });

        int threads = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                new OAuthClientCredentialsTokenSource("comp-1", cfg, minter).currentAuthorizationHeader();
                done.countDown();
            });
        }
        Thread.sleep(50);
        latch.countDown();
        assertThat(done.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        verify(minter, times(1)).mint(cfg);
    }

    @Test
    void tokenResponseMissingAccessToken_throws() {
        String body = "{\"token_type\":\"Bearer\",\"expires_in\":300}";
        assertThatThrownBy(() -> OAuthClientCredentialsTokenSource.parseTokenResponse(body))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("access_token");
    }

    @Test
    void missingExpiresIn_defaultsTo60s() {
        String body = "{\"access_token\":\"abc\",\"token_type\":\"Bearer\"}";
        var parsed = OAuthClientCredentialsTokenSource.parseTokenResponse(body);
        assertThat(parsed.hadExpiresIn()).isFalse();
        assertThat(parsed.result().expiresInSeconds()).isEqualTo(60L);
        assertThat(parsed.result().authorizationHeader()).isEqualTo("Bearer abc");
    }

    @Test
    void presentExpiresIn_hadExpiresInTrue() {
        String body = "{\"access_token\":\"tok\",\"expires_in\":300}";
        var parsed = OAuthClientCredentialsTokenSource.parseTokenResponse(body);
        assertThat(parsed.hadExpiresIn()).isTrue();
        assertThat(parsed.result().expiresInSeconds()).isEqualTo(300L);
    }

    @Test
    void invalidate_dropsEntry() {
        when(minter.mint(cfg))
            .thenReturn(new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.first", 300))
            .thenReturn(new OAuthClientCredentialsTokenSource.MintResult("Bearer eyJ.second", 300));

        var src = new OAuthClientCredentialsTokenSource("comp-1", cfg, minter);
        assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.first");
        src.invalidate();
        assertThat(src.currentAuthorizationHeader()).isEqualTo("Bearer eyJ.second");
        verify(minter, times(2)).mint(cfg);
    }

    @Test
    void nonNumericExpiresIn_treatedAsMissing() {
        String body = "{\"access_token\":\"abc\",\"token_type\":\"Bearer\",\"expires_in\":\"not-a-number\"}";
        var parsed = OAuthClientCredentialsTokenSource.parseTokenResponse(body);
        assertThat(parsed.hadExpiresIn()).isFalse();
        assertThat(parsed.result().expiresInSeconds()).isEqualTo(60L);
    }

    @Test
    void httpNonSuccess_throws() {
        var wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        try {
            String longBody = "invalid_client_secret ".repeat(20); // ~440 chars
            wm.stubFor(post("/token")
                .willReturn(aResponse()
                    .withStatus(401)
                    .withBody(longBody)));

            var minter = new OAuthClientCredentialsTokenSource.HttpTokenMinter("comp-1");
            var c = new OAuthConfig(wm.baseUrl() + "/token", "client", "secret", null);

            assertThatThrownBy(() -> minter.mint(c))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("…");  // truncation marker
        } finally {
            wm.stop();
        }
    }

    @Test
    void nonDefaultTokenType_usedInHeader() {
        String body = "{\"access_token\":\"xyz\",\"token_type\":\"DPoP\",\"expires_in\":300}";
        var r = OAuthClientCredentialsTokenSource.parseTokenResponse(body).result();
        assertThat(r.authorizationHeader()).isEqualTo("DPoP xyz");
    }

    @Test
    void scopeAppendedToBodyWhenConfigured() {
        var wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        try {
            wm.stubFor(post("/token").willReturn(okJson(
                "{\"access_token\":\"t\",\"token_type\":\"Bearer\",\"expires_in\":300}")));

            var minter = new OAuthClientCredentialsTokenSource.HttpTokenMinter("comp-1");
            minter.mint(new OAuthConfig(wm.baseUrl() + "/token", "id", "sec", "scim:write"));

            wm.verify(postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("scope=scim%3Awrite")));
        } finally {
            wm.stop();
        }
    }

    @Test
    void scopeOmittedWhenBlank() {
        var wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        try {
            wm.stubFor(post("/token").willReturn(okJson(
                "{\"access_token\":\"t\",\"expires_in\":300}")));

            var minter = new OAuthClientCredentialsTokenSource.HttpTokenMinter("comp-1");
            // null scope
            minter.mint(new OAuthConfig(wm.baseUrl() + "/token", "id", "sec", null));
            // empty scope
            minter.mint(new OAuthConfig(wm.baseUrl() + "/token", "id", "sec", ""));

            wm.verify(2, postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(notMatching(".*scope=.*")));
        } finally {
            wm.stop();
        }
    }

    @Test
    void clientCredentialsUrlEncoded() {
        var wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        try {
            wm.stubFor(post("/token").willReturn(okJson(
                "{\"access_token\":\"t\",\"expires_in\":300}")));

            var minter = new OAuthClientCredentialsTokenSource.HttpTokenMinter("comp-1");
            // client_id with space, client_secret with '/' and '+' — special chars per RFC 6749 §2.3.1
            minter.mint(new OAuthConfig(wm.baseUrl() + "/token", "id with space", "sec/with+special", null));

            // URLEncoder.encode("id with space", UTF-8) → "id+with+space"
            // URLEncoder.encode("sec/with+special", UTF-8) → "sec%2Fwith%2Bspecial"
            String expected = "Basic " + java.util.Base64.getEncoder().encodeToString(
                "id+with+space:sec%2Fwith%2Bspecial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            wm.verify(postRequestedFor(urlEqualTo("/token"))
                .withHeader("Authorization", equalTo(expected)));
        } finally {
            wm.stop();
        }
    }
}
