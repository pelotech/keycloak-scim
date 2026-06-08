package sh.libre.scim.core;

/** No-op tracing bridge used on Keycloak 25.x where OpenTelemetry is not available. */
class NoopTracingBridge implements ScimTracingBridge {

    private static final SpanHandle NOOP = new SpanHandle() {
        @Override public void setHttpStatus(int code) {}
        @Override public void recordError(Throwable t) {}
        @Override public void close() {}
    };

    @Override
    public SpanHandle startSpan(String operation, String resourceType, String serverAddress) {
        return NOOP;
    }
}
