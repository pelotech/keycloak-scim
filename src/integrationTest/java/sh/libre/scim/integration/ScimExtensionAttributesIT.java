package sh.libre.scim.integration;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that admin-configured extension-attribute mappings reach the
 * SCIM sink on the wire. Drives a real Keycloak (admin-REST create/update path
 * through {@code ScimEventListenerProvider}) against the WireMock SCIM sink and
 * asserts the captured request bodies carry the mapped extension JSON with the
 * right shape (extra schemas, enterprise field, custom object, boolean type,
 * multivalued array).
 *
 * <p>The mapping table covers all three interesting cases at once:
 * <ul>
 *   <li>the IETF enterprise URN single-valued string field ({@code department});</li>
 *   <li>a custom-URN multivalued attribute ({@code labels});</li>
 *   <li>a custom-URN typed (boolean) attribute ({@code active}).</li>
 * </ul>
 *
 * <p>The user's attributes are set via admin REST, which requires the realm's
 * declarative user profile to permit unmanaged attributes (Keycloak 25 drops
 * unknown attributes otherwise) — see {@link #enableUnmanagedUserAttributes}.
 */
class ScimExtensionAttributesIT extends IntegrationTestBase {

    private static final String ENTERPRISE_URN =
        "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";
    private static final String CUSTOM_URN = "urn:example:custom:2.0:User";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The exact three mapping rows under test, in config-row syntax. */
    private static List<String> extensionMappingRows() {
        return List.of(
            "kcDept = " + ENTERPRISE_URN + ":department",
            "kcLabels = " + CUSTOM_URN + ":labels ; multi",
            "kcActive = " + CUSTOM_URN + ":active ; type=boolean");
    }

    /** Create an admin user carrying the mapped Keycloak attributes. */
    private String createUserWithExtensionAttrs(
            org.keycloak.admin.client.resource.RealmResource realm,
            String username, String email) {
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        user.setEmailVerified(true);
        user.setEnabled(true);
        user.setAttributes(Map.of(
            "kcDept", List.of("Eng"),
            "kcActive", List.of("true"),
            "kcLabels", List.of("/a", "/b")));
        try (var resp = realm.users().create(user)) {
            if (resp.getStatus() >= 400) {
                throw new IllegalStateException(
                    "create user " + username + " failed: " + resp.getStatus());
            }
            String path = resp.getLocation().getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /** The body of the last POST /Users WireMock recorded, parsed as JSON. */
    private JsonNode latestUserPostBody() throws Exception {
        var posts = wireMock.getAllServeEvents().stream()
            .filter(e -> e.getRequest().getUrl().startsWith("/Users")
                && "POST".equals(e.getRequest().getMethod().getName()))
            .toList();
        assertTrue(posts.size() >= 1, "expected a SCIM POST /Users");
        return JSON.readTree(posts.get(posts.size() - 1).getRequest().getBodyAsString());
    }

    @Test
    void postUsersCarriesMappedExtensionAttributes() throws Exception {
        stubScimUserCreateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg ->
            cfg.put("user-extension-mappings", extensionMappingRows()));
        enableScimEventListener(r.realm());
        enableUnmanagedUserAttributes(r.realm());

        createUserWithExtensionAttrs(r.realm(), "extuser", "extuser@test.local");

        awaitUserPostFor("extuser");

        JsonNode body = latestUserPostBody();

        // schemas[] must include BOTH extension URNs.
        var schemas = new java.util.HashSet<String>();
        body.get("schemas").forEach(n -> schemas.add(n.asText()));
        assertTrue(schemas.contains(ENTERPRISE_URN),
            "schemas must include the enterprise URN, got: " + schemas);
        assertTrue(schemas.contains(CUSTOM_URN),
            "schemas must include the custom URN, got: " + schemas);

        // Enterprise extension object carries department="Eng".
        JsonNode enterprise = body.get(ENTERPRISE_URN);
        assertTrue(enterprise != null && enterprise.isObject(),
            "enterprise extension object missing, body was: " + body);
        assertEquals("Eng", enterprise.get("department").asText(),
            "enterprise department must be the mapped value");

        // Custom extension object: active is a JSON boolean (not the string "true"),
        // labels is a JSON array ["/a","/b"].
        JsonNode custom = body.get(CUSTOM_URN);
        assertTrue(custom != null && custom.isObject(),
            "custom extension object missing, body was: " + body);

        JsonNode active = custom.get("active");
        assertTrue(active.isBoolean(),
            "active must be a JSON boolean, not a string; node was: " + active);
        assertTrue(active.booleanValue(), "active must be boolean true");

        JsonNode labels = custom.get("labels");
        assertTrue(labels.isArray(), "labels must be a JSON array, node was: " + labels);
        var paths = new java.util.ArrayList<String>();
        labels.forEach(n -> paths.add(n.asText()));
        assertEquals(List.of("/a", "/b"), paths, "labels array must carry both values");
    }

    @Test
    void patchUsersCarriesMappedExtensionReplaceOp() throws Exception {
        // With user-patchOp=true the replace path (admin update) goes out as a
        // SCIM PATCH instead of PUT, so we can assert the extension REPLACE op
        // lands on a fully-qualified extension path.
        stubScimUserCreateOk();
        // PATCH /Users/* sink: respond OK so the replace completes cleanly.
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock
            .patch(urlPathMatching("/Users/.*"))
            .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "ext-patched",
                      "userName": "placeholder",
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""")));

        var r = newRealmWithScimAndLdapAndConfig(cfg -> {
            cfg.put("user-extension-mappings", extensionMappingRows());
            cfg.putSingle("user-patchOp", "true");
        });
        enableScimEventListener(r.realm());
        enableUnmanagedUserAttributes(r.realm());

        String userId = createUserWithExtensionAttrs(r.realm(), "patchuser", "patchuser@test.local");
        awaitUserPostFor("patchuser");

        // Flip the mapped boolean attribute and trigger an update => replace path.
        var rep = r.realm().users().get(userId).toRepresentation();
        var attrs = new java.util.HashMap<>(rep.getAttributes());
        attrs.put("kcActive", List.of("false"));
        rep.setAttributes(attrs);
        r.realm().users().get(userId).update(rep);

        // Await a PATCH whose body carries a REPLACE op on the fully-qualified
        // custom extension path for 'active'.
        String activePath = CUSTOM_URN + ":active";
        await().atMost(20, SECONDS).untilAsserted(() -> {
            var patches = wireMock.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().startsWith("/Users/")
                    && "PATCH".equals(e.getRequest().getMethod().getName()))
                .toList();
            assertTrue(patches.size() >= 1, "expected a SCIM PATCH /Users/*");

            boolean found = false;
            for (var p : patches) {
                JsonNode b = JSON.readTree(p.getRequest().getBodyAsString());
                JsonNode ops = b.get("Operations");
                if (ops == null) continue;
                for (JsonNode op : ops) {
                    String path = op.path("path").asText("");
                    String oper = op.path("op").asText("");
                    if (activePath.equals(path) && "replace".equalsIgnoreCase(oper)) {
                        found = true;
                    }
                }
            }
            assertTrue(found,
                "expected a REPLACE op on path '" + activePath + "' in some PATCH body; "
                + "bodies were: " + patches.stream()
                    .map(p -> p.getRequest().getBodyAsString()).toList());
        });
    }
}
