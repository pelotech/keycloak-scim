package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import jakarta.persistence.EntityManager;

/**
 * Pins the wire shape of the single-member delta PATCH built by
 * {@link GroupAdapter#toMembershipPatchBuilder}. {@code getResource()}
 * returns the serialized PATCH body, so we can assert the operation, path,
 * and member value without any HTTP. The end-to-end behaviours
 * (group-patchOp fallback to PUT, skip on missing mapping, PATCH actually
 * sent) are covered by {@code ScimGroupPropagationIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupMembershipPatchTest {

    private static final String GROUP_URL = "https://scim.example/scim/v2/Groups/grp-ext-1";

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;

    private GroupAdapter adapter;
    private ScimRequestBuilder scimRequestBuilder;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);

        adapter = new GroupAdapter(session, "component-id");
        scimRequestBuilder = new ScimRequestBuilder(
            "https://scim.example/scim/v2", ScimClientConfig.builder().build());
    }

    @Test
    void addBuildsSingleMemberAddOperation() {
        var patch = adapter.toMembershipPatchBuilder(
            scimRequestBuilder, GROUP_URL, "user-ext-9", true);

        String body = patch.getResource();
        assertThat(body).contains("\"op\":\"add\"");
        assertThat(body).contains("\"path\":\"members\"");
        assertThat(body).contains("user-ext-9");
        // A delta add carries exactly one member value — no full-list re-send.
        assertThat(body).doesNotContain("value eq");
        // Regression guard: the SDK's .next() is a separator, not a terminator.
        // A stray trailing .next() before .build() appends an empty operation
        // that a strict SCIM target rejects as "Missing operation for patch
        // operation". Pin the count to exactly one.
        assertThat(countOps(body)).isEqualTo(1);
    }

    @Test
    void removeBuildsFilteredRemoveOperation() {
        var patch = adapter.toMembershipPatchBuilder(
            scimRequestBuilder, GROUP_URL, "user-ext-9", false);

        String body = patch.getResource();
        assertThat(body).contains("\"op\":\"remove\"");
        // RFC 7644 filter path targets exactly this member.
        assertThat(body).contains("members[value eq \\\"user-ext-9\\\"]");
        assertThat(countOps(body)).isEqualTo(1);
    }

    private static int countOps(String body) {
        int count = 0;
        int idx = 0;
        while ((idx = body.indexOf("\"op\":", idx)) != -1) {
            count++;
            idx += "\"op\":".length();
        }
        return count;
    }

    @Test
    void groupUpdatePatchCarriesOnlyAttributesNotMembers() {
        adapter.setId("kc-group-1");
        adapter.setDisplayName("Engineers");

        var patch = adapter.toPatchBuilder(scimRequestBuilder, GROUP_URL);

        String body = patch.getResource();
        assertThat(body).contains("\"op\":\"replace\"");
        assertThat(body).contains("\"path\":\"displayName\"");
        assertThat(body).contains("Engineers");
        assertThat(body).contains("\"path\":\"externalId\"");
        // The point of #1: a group update (rename / refresh) must NOT re-send
        // the member list — membership is maintained by the delta PATCHes.
        assertThat(body).doesNotContain("\"members\"");
    }
}
