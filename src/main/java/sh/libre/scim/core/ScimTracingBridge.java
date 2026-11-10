package sh.libre.scim.core;

/**
 * Thin tracing facade over OpenTelemetry, which every supported Keycloak
 * version (26+) bundles through Quarkus. When Keycloak tracing is off, the
 * global OpenTelemetry instance is a no-op, so the spans cost almost nothing.
 *
 * <p>No OpenTelemetry types appear in this interface, so callers and their
 * tests do not depend on the OpenTelemetry API.
 */
interface ScimTracingBridge {

    /**
     * Handle for a started span. Callers use try-with-resources.
     */
    interface SpanHandle extends AutoCloseable {
        void setHttpStatus(int code);
        void recordError(Throwable t);
        @Override void close();
    }

    SpanHandle startSpan(String operation, String resourceType, String serverAddress);

    static ScimTracingBridge create() {
        return new OtelTracingBridge();
    }
}
