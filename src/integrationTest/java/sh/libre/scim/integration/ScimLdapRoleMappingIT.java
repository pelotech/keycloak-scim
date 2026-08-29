package sh.libre.scim.integration;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code scim="true"} realm role assigned through {@code role-ldap-mapper}
 * lands in the pushed SCIM {@code User.roles} and survives re-sync. The seeded
 * {@code cn=directory-role,ou=roles,dc=test,dc=local} entry lists alice as a
 * member; the mapper materializes it as a realm role on import.
 */
class ScimLdapRoleMappingIT extends IntegrationTestBase {

    /** Attaches a role-ldap-mapper covering the seeded ou=roles subtree. */
    private void addLdapRoleMapper(RealmResource realm, String ldapId) {
        var mapper = new ComponentRepresentation();
        mapper.setName("roles");
        mapper.setProviderType("org.keycloak.storage.ldap.mappers.LDAPStorageMapper");
        mapper.setProviderId("role-ldap-mapper");
        mapper.setParentId(ldapId);
        var cfg = new MultivaluedHashMap<String, String>();
        cfg.putSingle("roles.dn", "ou=roles,dc=test,dc=local");
        cfg.putSingle("membership.ldap.attribute", "member");
        cfg.putSingle("membership.attribute.type", "DN");
        cfg.putSingle("role.name.ldap.attribute", "cn");
        cfg.putSingle("role.object.classes", "groupOfNames");
        cfg.putSingle("mode", "READ_ONLY");
        cfg.putSingle("use.realm.roles.mapping", "true");
        // inert with membership.attribute.type=DN; kept to match addLdapGroupMapper
        cfg.putSingle("membership.user.ldap.attribute", "uid");
        cfg.putSingle("user.roles.retrieve.strategy", "LOAD_ROLES_BY_MEMBER_ATTRIBUTE");
        mapper.setConfig(cfg);
        try (Response r = realm.components().add(mapper)) {
            if (r.getStatus() >= 400) {
                throw new IllegalStateException("LDAP role mapper create failed: " + r.getStatus());
            }
        }
    }

    /**
     * Count of pushes (POST /Users or PUT /Users/{id}) whose body has both
     * userName "alice" and a roles[] entry with value "directory-role".
     * Correlated by request body, not external id: the generic create stub
     * hands every user the same placeholder id.
     */
    private int alicePushesCarryingDirectoryRole() {
        int posts = wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("alice")))
                .withRequestBody(matchingJsonPath("$.roles[?(@.value == 'directory-role')]"))
                .build()).getCount();
        int puts = wireMock.countRequestsMatching(
            putRequestedFor(urlPathMatching("/Users/.*"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("alice")))
                .withRequestBody(matchingJsonPath("$.roles[?(@.value == 'directory-role')]"))
                .build()).getCount();
        return posts + puts;
    }

    @Test
    void ldapRoleWithScimAttribute_landsInPushedRoles_andSurvivesResync() {
        stubScimUserCreateOk();
        stubScimUserUpdateOk();

        var r = newRealmWithScimAndLdapAndConfig(cfg -> cfg.putSingle("sync-refresh", "true"));
        addLdapRoleMapper(r.realm(), r.ldapId());

        // Full LDAP sync imports alice and materializes directory-role as a
        // realm role via the role mapper.
        triggerFullSync(r);
        awaitUserPostFor("alice");
        await().atMost(30, SECONDS).ignoreExceptions().untilAsserted(() ->
            assertNotNull(r.realm().roles().get("directory-role").toRepresentation(),
                "realm role directory-role should materialize from the LDAP role mapper"));

        // Mark the role SCIM-relevant.
        var rep = r.realm().roles().get("directory-role").toRepresentation();
        rep.setAttributes(Map.of("scim", List.of("true")));
        r.realm().roles().get("directory-role").update(rep);

        // Sync the SCIM component: sync-refresh replaces every mapped user, so
        // alice's push should now carry the role.
        String scimComponentId = scimComponent(r.realm()).getId();
        r.realm().userStorage().syncUsers(scimComponentId, "triggerFullSync");
        await().atMost(30, SECONDS).untilAsserted(() -> {
            int pushes = alicePushesCarryingDirectoryRole();
            assertTrue(pushes >= 1,
                "expected a push of alice carrying roles[].value == directory-role, got " + pushes);
        });

        // Re-sync: a second push carrying the role should arrive.
        r.realm().userStorage().syncUsers(scimComponentId, "triggerFullSync");
        await().atMost(30, SECONDS).untilAsserted(() -> {
            int pushes = alicePushesCarryingDirectoryRole();
            assertTrue(pushes >= 2,
                "expected the role to survive re-sync (>= 2 pushes carrying it), got " + pushes);
        });
    }
}
