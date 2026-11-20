package sh.libre.scim.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import sh.libre.scim.storage.ScimStorageProviderFactory;

/**
 * Unit tests for {@link GroupAdapter#skippedByGroupFilter(GroupModel)} and
 * the interaction of that filter with the {@code scim-skip} attribute in
 * {@link GroupAdapter#apply(GroupModel)} and
 * {@link GroupAdapter#applyForProvisioning(GroupModel)}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupAdapterGroupFilterTest {

    private static final String COMPONENT_ID = "component-id";

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock ComponentModel component;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;

    private GroupAdapter adapter;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);

        adapter = new GroupAdapter(session, COMPONENT_ID);
    }

    // -----------------------------------------------------------------------
    // skippedByGroupFilter — filter absent or blank
    // -----------------------------------------------------------------------

    @Test
    void noFilterNull_doesNotSkip() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn(null);

        assertThat(adapter.skippedByGroupFilter(groupNamed("engineering"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void noFilterBlankOrWhitespace_doesNotSkip(String blank) {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn(blank);

        assertThat(adapter.skippedByGroupFilter(groupNamed("engineering"))).isFalse();
    }

    @Test
    void nullComponent_doesNotSkip() {
        // getModel() returns null when the component has been removed.
        when(realm.getComponent(COMPONENT_ID)).thenReturn(null);

        assertThat(adapter.skippedByGroupFilter(groupNamed("engineering"))).isFalse();
    }

    // -----------------------------------------------------------------------
    // skippedByGroupFilter — exact full-match semantics
    // -----------------------------------------------------------------------

    @Test
    void exactMatch_doesNotSkip() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("engineering");

        assertThat(adapter.skippedByGroupFilter(groupNamed("engineering"))).isFalse();
    }

    @Test
    void noMatchDifferentName_skips() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("engineering");

        assertThat(adapter.skippedByGroupFilter(groupNamed("ops"))).isTrue();
    }

    @Test
    void substringIsNotAFullMatch_skips() {
        // "eng" does NOT fully match "engineering" with String.matches semantics.
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("eng");

        assertThat(adapter.skippedByGroupFilter(groupNamed("engineering"))).isTrue();
    }

    @Test
    void suffixPatternMatchesPrefix_doesNotSkip() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("engineering.*");

        assertThat(adapter.skippedByGroupFilter(groupNamed("engineering-leads"))).isFalse();
    }

    @Test
    void suffixPatternDoesNotMatchUnrelatedName_skips() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("engineering.*");

        assertThat(adapter.skippedByGroupFilter(groupNamed("ops-leads"))).isTrue();
    }

    @Test
    void anchoredAlternation_matchesFirstBranch_doesNotSkip() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("^(eng|ops).*");

        assertThat(adapter.skippedByGroupFilter(groupNamed("engineering"))).isFalse();
    }

    @Test
    void anchoredAlternation_matchesSecondBranch_doesNotSkip() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("^(eng|ops).*");

        assertThat(adapter.skippedByGroupFilter(groupNamed("ops-leads"))).isFalse();
    }

    @Test
    void anchoredAlternation_noMatch_skips() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("^(eng|ops).*");

        assertThat(adapter.skippedByGroupFilter(groupNamed("finance"))).isTrue();
    }

    @Test
    void patternWithLeadingAndTrailingWhitespace_isTrimmedBeforeMatching() {
        // The implementation trims the pattern before calling matches().
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("  engineering  ");

        assertThat(adapter.skippedByGroupFilter(groupNamed("engineering"))).isFalse();
    }

    // -----------------------------------------------------------------------
    // apply(GroupModel) — filter integrated with scim-skip
    // -----------------------------------------------------------------------

    @Test
    void apply_filterMatches_skipIsFalse() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("team-.*");

        var group = groupNamedWithSkip("team-alpha", null);
        when(session.users()).thenReturn(org.mockito.Mockito.mock(
                org.keycloak.models.UserProvider.class));
        when(session.users().getGroupMembersStream(realm, group))
                .thenReturn(java.util.stream.Stream.empty());

        adapter.apply(group);

        assertThat(adapter.skip).isFalse();
    }

    @Test
    void apply_filterDoesNotMatch_skipIsTrue() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("team-.*");

        var group = groupNamedWithSkip("engineering", null);
        when(session.users()).thenReturn(org.mockito.Mockito.mock(
                org.keycloak.models.UserProvider.class));
        when(session.users().getGroupMembersStream(realm, group))
                .thenReturn(java.util.stream.Stream.empty());

        adapter.apply(group);

        assertThat(adapter.skip).isTrue();
    }

    @Test
    void apply_scimSkipTrue_skipIsTrue_evenWhenFilterMatches() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("engineering");

        var group = groupNamedWithSkip("engineering", "true");
        when(session.users()).thenReturn(org.mockito.Mockito.mock(
                org.keycloak.models.UserProvider.class));
        when(session.users().getGroupMembersStream(realm, group))
                .thenReturn(java.util.stream.Stream.empty());

        adapter.apply(group);

        assertThat(adapter.skip).isTrue();
    }

    @Test
    void apply_scimSkipTrue_filterDoesNotMatch_skipIsTrue() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("engineering");

        var group = groupNamedWithSkip("ops", "true");
        when(session.users()).thenReturn(org.mockito.Mockito.mock(
                org.keycloak.models.UserProvider.class));
        when(session.users().getGroupMembersStream(realm, group))
                .thenReturn(java.util.stream.Stream.empty());

        adapter.apply(group);

        assertThat(adapter.skip).isTrue();
    }

    // -----------------------------------------------------------------------
    // applyForProvisioning(GroupModel) — filter integrated with scim-skip
    // -----------------------------------------------------------------------

    @Test
    void applyForProvisioning_filterMatches_skipIsFalse() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("team-.*");

        var group = groupNamedWithSkip("team-alpha", null);
        adapter.applyForProvisioning(group);

        assertThat(adapter.skip).isFalse();
    }

    @Test
    void applyForProvisioning_filterDoesNotMatch_skipIsTrue() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("team-.*");

        var group = groupNamedWithSkip("engineering", null);
        adapter.applyForProvisioning(group);

        assertThat(adapter.skip).isTrue();
    }

    @Test
    void applyForProvisioning_scimSkipTrue_skipIsTrue_evenWhenFilterMatches() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get(ScimStorageProviderFactory.GROUP_FILTER)).thenReturn("engineering");

        var group = groupNamedWithSkip("engineering", "true");
        adapter.applyForProvisioning(group);

        assertThat(adapter.skip).isTrue();
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private GroupModel groupNamed(String name) {
        return groupNamedWithSkip(name, null);
    }

    private GroupModel groupNamedWithSkip(String name, String scimSkip) {
        var group = org.mockito.Mockito.mock(GroupModel.class);
        when(group.getId()).thenReturn("grp-" + name);
        when(group.getName()).thenReturn(name);
        when(group.getFirstAttribute("scim-skip")).thenReturn(scimSkip);
        return group;
    }
}
