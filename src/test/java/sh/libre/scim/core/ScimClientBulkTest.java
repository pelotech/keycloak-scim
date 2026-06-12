package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of the bulk-create request via a real {@link ScimRequestBuilder}
 * and {@code BulkBuilder.getResource()} — no HTTP, no Keycloak (mirrors
 * GroupMembershipPatchTest). The re-fetch / apply / filter / mapping-save path
 * needs a live session and is covered by the integration scenario (Task 8).
 */
class ScimClientBulkTest {

    @Test
    void buildsOneBulkRequestWithPostOpPerUser() {
        var builder = new ScimRequestBuilder(
            "https://scim.example/scim/v2", ScimClientConfig.builder().build());
        String body = ScimClient.assembleBulkCreate(builder, List.of(
            new String[]{"kc-1", "{\"userName\":\"a\"}"},
            new String[]{"kc-2", "{\"userName\":\"b\"}"})).getResource();
        assertThat(body).contains("\"method\":\"POST\"");
        assertThat(body).contains("\"path\":\"/Users\"");
        assertThat(body).contains("\"bulkId\":\"kc-1\"");
        assertThat(body).contains("\"bulkId\":\"kc-2\"");
    }
}
