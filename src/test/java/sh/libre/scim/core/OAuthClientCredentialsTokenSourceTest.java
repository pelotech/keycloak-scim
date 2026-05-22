package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.*;
import org.junit.jupiter.api.*;

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
}
