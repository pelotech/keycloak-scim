package sh.libre.scim.core;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

/**
 * OpenTelemetry-backed tracing bridge. This class is only instantiated (and therefore
 * only loaded by the JVM) after {@link ScimTracingBridge#create()} confirms that
 * {@code io.opentelemetry.api.GlobalOpenTelemetry} is present on the classpath, which
 * is the case on Keycloak 26+ but not on 25.x.
 */
class OtelTracingBridge implements ScimTracingBridge {

    private final Tracer tracer = GlobalOpenTelemetry.getTracer("keycloak-scim");

    @Override
    public SpanHandle startSpan(String operation, String resourceType, String serverAddress) {
        Span span = tracer.spanBuilder(operation)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("scim.resource_type", resourceType)
            .setAttribute("server.address", serverAddress)
            .startSpan();
        Scope scope = span.makeCurrent();
        return new SpanHandle() {
            @Override
            public void setHttpStatus(int code) {
                span.setAttribute("http.response.status_code", code);
                if (code >= 400) {
                    span.setStatus(StatusCode.ERROR);
                    span.setAttribute("error.type", "HTTP_" + code);
                }
            }

            @Override
            public void recordError(Throwable t) {
                span.recordException(t);
                span.setStatus(StatusCode.ERROR);
                span.setAttribute("error.type", t.getClass().getSimpleName());
            }

            @Override
            public void close() {
                scope.close();
                span.end();
            }
        };
    }
}
