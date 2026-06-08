package sh.libre.scim.core;

/**
 * Thin tracing facade. On Keycloak 26+ (which bundles OpenTelemetry via Quarkus)
 * {@link #create()} returns an {@link OtelTracingBridge} that emits real spans.
 * On Keycloak 25.x, where OTel is absent, it returns a no-op bridge so the plugin
 * compiles and runs against both versions without changes.
 *
 * <p>No OpenTelemetry types appear in this interface so callers never trigger a
 * {@link NoClassDefFoundError} on runtimes that don't have OTel on the classpath.
 */
interface ScimTracingBridge {

    /**
     * Handle for a started span. Callers use try-with-resources; all methods are
     * safe to call on the no-op implementation.
     */
    interface SpanHandle extends AutoCloseable {
        void setHttpStatus(int code);
        void recordError(Throwable t);
        @Override void close();
    }

    SpanHandle startSpan(String operation, String resourceType, String serverAddress);

    static ScimTracingBridge create() {
        try {
            Class.forName("io.opentelemetry.api.GlobalOpenTelemetry");
            return new OtelTracingBridge();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return new NoopTracingBridge();
        }
    }
}
