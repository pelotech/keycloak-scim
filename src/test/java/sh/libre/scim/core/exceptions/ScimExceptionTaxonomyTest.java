package sh.libre.scim.core.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ScimExceptionTaxonomyTest {

    @Test
    void mappingAndDataExceptionsArePermanent() {
        assertThat(new InconsistentScimMappingException("m").isTransient()).isFalse();
        assertThat(new UnexpectedScimDataException("d").isTransient()).isFalse();
    }

    @Test
    void endpointException5xxAnd429AndTransportAreTransient() {
        assertThat(new InvalidResponseFromScimEndpointException(500, "x").isTransient()).isTrue();
        assertThat(new InvalidResponseFromScimEndpointException(503, "x").isTransient()).isTrue();
        assertThat(new InvalidResponseFromScimEndpointException(429, "x").isTransient()).isTrue();
        assertThat(InvalidResponseFromScimEndpointException.transport("conn refused",
                new RuntimeException()).isTransient()).isTrue();
    }

    @Test
    void endpoint4xxIsPermanent() {
        assertThat(new InvalidResponseFromScimEndpointException(400, "x").isTransient()).isFalse();
        assertThat(new InvalidResponseFromScimEndpointException(404, "x").isTransient()).isFalse();
        assertThat(new InvalidResponseFromScimEndpointException(409, "x").isTransient()).isFalse();
    }

    @Test
    void allSubtypesShareTheBaseType() {
        ScimPropagationException e = new InconsistentScimMappingException("m");
        assertThat(e).isInstanceOf(RuntimeException.class);
    }
}
