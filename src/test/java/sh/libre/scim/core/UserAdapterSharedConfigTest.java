package sh.libre.scim.core;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A realm-cached ComponentModel's config map is shared: every dispatch thread
 * that adapts a user reads the same map off the same model. Reading it
 * therefore has to be a read.
 *
 * Keycloak's MultivaluedMap.getList is not — it is
 * {@code compute(key, (k, v) -> v == null ? new ArrayList<>() : v)}, so calling
 * it for an absent key inserts an empty list and structurally modifies the map.
 * Concurrent scim-dispatch threads then race inside HashMap.compute and one
 * gets a ConcurrentModificationException out of UserAdapter.apply, failing that
 * user's create and leaving it with no SCIM mapping for the rest of the run.
 *
 * Asserting on the mutation rather than on the exception is deliberate. Only
 * the first read of an absent key modifies anything, so the race window shuts
 * as soon as one thread wins it; a thread-racing test passes comfortably even
 * with the bug present (measured: 8 threads x 300 rounds, 3 runs, no
 * reproduction). The mutation is the root cause and is deterministic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAdapterSharedConfigTest {

    private static final String COMPONENT_ID = "component-id";
    private static final String MAPPINGS_KEY = "user-extension-mappings";

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);
    }

    @Test
    void applyDoesNotMutateTheSharedComponentConfig() {
        // No extension mappings configured — the common case, and the one where
        // the absent key makes getList insert.
        var model = new ComponentModel();
        model.setConfig(new MultivaluedHashMap<>());
        model.setId(COMPONENT_ID);
        when(realm.getComponent(COMPONENT_ID)).thenReturn(model);

        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn("u1");
        when(user.getUsername()).thenReturn("alice");
        when(user.isEnabled()).thenReturn(true);
        when(user.getGroupsStream()).thenAnswer(inv -> Stream.empty());
        when(user.getRoleMappingsStream()).thenAnswer(inv -> Stream.empty());
        when(user.getAttributes()).thenAnswer(inv -> Map.<String, List<String>>of());

        new UserAdapter(session, COMPONENT_ID).apply(user);

        assertThat(model.getConfig())
            .as("reading extension mappings must not insert into the shared config map")
            .doesNotContainKey(MAPPINGS_KEY);
    }
}
