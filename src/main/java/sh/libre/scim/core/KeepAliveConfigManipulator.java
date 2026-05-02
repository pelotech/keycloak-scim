package sh.libre.scim.core;

import de.captaingoldfish.scim.sdk.client.http.ConfigManipulator;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClientBuilder;

/**
 * Overrides the SCIM SDK's hardcoded "no connection reuse" policy and tunes
 * the underlying Apache HttpClient's connection pool for our async-worker
 * dispatch pattern.
 *
 * <p>Why this exists: {@code ScimHttpClient.getHttpClient()} in
 * {@code de.captaingoldfish:scim-sdk-client:1.25.1} unconditionally calls
 *
 * <pre>{@code
 * clientBuilder.setConnectionReuseStrategy((response, context) -> false);
 * }</pre>
 *
 * which forces a new TCP connection for every SCIM request. On localhost
 * that costs ~43 ms per request (almost entirely TCP setup + teardown);
 * over the wire to a real SCIM sink it scales with RTT. With our 8-worker
 * async dispatch, this is what the worker pool ends up paying per call.
 *
 * <p>The SDK invokes a registered {@link ConfigManipulator}'s
 * {@link #modifyHttpClientConfig} <em>after</em> the no-reuse line, so we
 * can flip it back. We also raise the connection-pool-per-route limit
 * from Apache HttpClient's default of 2 (way below our 8 workers all
 * talking to a single SCIM endpoint) to a value that matches the worker
 * pool with headroom.
 *
 * <p>Effect (measured locally, 1000 imports against a WireMock sink):
 * <ul>
 *   <li>without this manipulator: ~43 ms per HTTP send (dominant cost)</li>
 *   <li>with this manipulator: HTTP keepalive + larger pool kicks in,
 *       per-call cost drops, parallel throughput approaches the
 *       worker-pool-times-(1/keepalive-cost) ceiling</li>
 * </ul>
 *
 * <p>Trade-off: keepalive holds connections open against the SCIM sink.
 * Most production SCIM servers handle this fine, but the SDK's default
 * was conservative for a reason — some services interpret idle
 * connections poorly. If a deployment hits trouble, the manipulator can
 * be replaced with a no-op via a config knob (not currently exposed —
 * file an issue if you need it).
 */
public final class KeepAliveConfigManipulator implements ConfigManipulator {

    /**
     * Per-route connection pool size. Sized to comfortably exceed the
     * default {@code scim.dispatch.threads} (8) so workers don't queue
     * waiting for a connection slot. Each "route" is one SCIM endpoint;
     * realistic deployments have 1–3 endpoints, so total connection use
     * stays bounded.
     */
    private static final int MAX_CONN_PER_ROUTE = Integer.getInteger(
        "scim.http.maxConnPerRoute", 32);

    /**
     * Total connection pool size across all routes.
     */
    private static final int MAX_CONN_TOTAL = Integer.getInteger(
        "scim.http.maxConnTotal", 64);

    @Override
    public void modifyHttpClientConfig(HttpClientBuilder clientBuilder) {
        // Undo the SDK's hardcoded no-reuse. DefaultConnectionReuseStrategy
        // honors the Connection: keep-alive / close header from the server.
        clientBuilder.setConnectionReuseStrategy(DefaultConnectionReuseStrategy.INSTANCE);
        // Default keepalive: honor server's Keep-Alive header, fall back to
        // an Apache-default duration. Adequate for SCIM servers that
        // negotiate keepalive correctly.
        clientBuilder.setKeepAliveStrategy(DefaultConnectionKeepAliveStrategy.INSTANCE);

        // Apache HttpClient defaults to maxPerRoute=2, maxTotal=20 — not
        // enough for a worker pool of 8+ talking to one or two SCIM
        // endpoints. Raise to give the workers headroom.
        clientBuilder.setMaxConnPerRoute(MAX_CONN_PER_ROUTE);
        clientBuilder.setMaxConnTotal(MAX_CONN_TOTAL);
    }

    @Override
    public void modifyRequestConfig(RequestConfig.Builder configBuilder) {
        // No-op: connect/socket/request timeouts are configured via
        // ScimClientConfig.builder().{connect,socket,request}Timeout(),
        // which the SDK applies before invoking us.
    }
}
