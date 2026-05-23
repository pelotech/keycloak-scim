package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
