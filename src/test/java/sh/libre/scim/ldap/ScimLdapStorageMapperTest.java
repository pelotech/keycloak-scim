package sh.libre.scim.ldap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import de.captaingoldfish.scim.sdk.common.resources.User;
import sh.libre.scim.core.AdapterFactory;
import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.core.ScimClient;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

import java.time.Instant;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScimLdapStorageMapperTest {

    @Mock ScimDispatcher dispatcher;
    @Mock UserModel user;
    @Mock RealmModel realm;
    @Mock LDAPObject ldapObject;

    private ScimLdapStorageMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ScimLdapStorageMapper(dispatcher);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onImportRoutesCreateWhenIsCreateTrue() {
        when(user.getId()).thenReturn("user-id-1");
        mapper.onImportUserFromLDAP(ldapObject, user, realm, true);

        // Captures the BiConsumer<ScimClient, KeycloakSession> submitted to
        // runAsync. Invokes it with mocks to verify it routes to client.create.
        // The lambda re-fetches the user from its worker session by id —
        // wire that lookup to return our same mock user.
        ArgumentCaptor<BiConsumer> captor = ArgumentCaptor.forClass(BiConsumer.class);
        verify(dispatcher).runAsync(eq(ScimDispatcher.SCOPE_USER), captor.capture());

        var workerSession = mock(KeycloakSession.class);
        var workerCtx = mock(KeycloakContext.class);
        var workerRealm = mock(RealmModel.class);
        var workerUsers = mock(UserProvider.class);
        when(workerSession.getContext()).thenReturn(workerCtx);
        when(workerCtx.getRealm()).thenReturn(workerRealm);
        when(workerSession.users()).thenReturn(workerUsers);
        when(workerUsers.getUserById(workerRealm, "user-id-1")).thenReturn(user);

        var client = mock(ScimClient.class);
        ((BiConsumer<ScimClient, KeycloakSession>) captor.getValue()).accept(client, workerSession);
        verify(client).create(
            ArgumentMatchers.<AdapterFactory<UserModel, User, UserAdapter>>any(), eq(user));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onImportRoutesReplaceWhenIsCreateFalse() {
        when(user.getId()).thenReturn("user-id-2");
        mapper.onImportUserFromLDAP(ldapObject, user, realm, false);

        ArgumentCaptor<BiConsumer> captor = ArgumentCaptor.forClass(BiConsumer.class);
        verify(dispatcher).runAsync(eq(ScimDispatcher.SCOPE_USER), captor.capture());

        var workerSession = mock(KeycloakSession.class);
        var workerCtx = mock(KeycloakContext.class);
        var workerRealm = mock(RealmModel.class);
        var workerUsers = mock(UserProvider.class);
        when(workerSession.getContext()).thenReturn(workerCtx);
        when(workerCtx.getRealm()).thenReturn(workerRealm);
        when(workerSession.users()).thenReturn(workerUsers);
        when(workerUsers.getUserById(workerRealm, "user-id-2")).thenReturn(user);

        var client = mock(ScimClient.class);
        ((BiConsumer<ScimClient, KeycloakSession>) captor.getValue()).accept(client, workerSession);
        verify(client).replace(
            ArgumentMatchers.<AdapterFactory<UserModel, User, UserAdapter>>any(), eq(user));
    }

    @Test
    void onRegisterUserToLDAPIsNoOp() {
        mapper.onRegisterUserToLDAP(ldapObject, user, realm);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void proxyReturnsDelegateUnchanged() {
        var delegate = mock(UserModel.class);
        assertSame(delegate, mapper.proxy(ldapObject, delegate, realm));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void getLdapProviderReturnsNull() {
        org.junit.jupiter.api.Assertions.assertNull(mapper.getLdapProvider());
    }

    @Test
    void onImportStampsLastSeenAttribute() {
        mapper.onImportUserFromLDAP(ldapObject, user, realm, true);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(user).setSingleAttribute(eq(ScimLdapStorageMapper.LAST_SEEN_ATTRIBUTE), value.capture());

        // Must be a parseable ISO-8601 instant — the reconciler reads it with Instant.parse.
        assertNotNull(value.getValue());
        assertDoesNotThrow(() -> Instant.parse(value.getValue()));
    }

    @Test
    void onImportStampsLastSeenOnReplacePathToo() {
        mapper.onImportUserFromLDAP(ldapObject, user, realm, false);
        verify(user).setSingleAttribute(eq(ScimLdapStorageMapper.LAST_SEEN_ATTRIBUTE),
            org.mockito.ArgumentMatchers.anyString());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private BiConsumer<ScimClient, KeycloakSession> captureGroupConsumer(String userId) {
        when(user.getId()).thenReturn(userId);
        mapper.onImportUserFromLDAP(ldapObject, user, realm, false);
        ArgumentCaptor<BiConsumer> captor = ArgumentCaptor.forClass(BiConsumer.class);
        verify(dispatcher).runAsync(eq(ScimDispatcher.SCOPE_GROUP), captor.capture());
        return (BiConsumer<ScimClient, KeycloakSession>) captor.getValue();
    }

    private KeycloakSession workerSessionReturning(String userId) {
        var ws = mock(KeycloakSession.class);
        var ctx = mock(KeycloakContext.class);
        var wRealm = mock(RealmModel.class);
        var users = mock(UserProvider.class);
        when(ws.getContext()).thenReturn(ctx);
        when(ctx.getRealm()).thenReturn(wRealm);
        when(ws.users()).thenReturn(users);
        when(users.getUserById(wRealm, userId)).thenReturn(user);
        return ws;
    }

    private GroupModel group(String id) {
        var g = mock(GroupModel.class);
        when(g.getId()).thenReturn(id);
        return g;
    }

    @Test
    void removesGroupsTheUserHasLeft() {
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = mock(ScimClient.class);
        when(client.getComponentId()).thenReturn("comp-1");
        var groupA = group("A");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA));
        when(user.getAttributeStream("scim-propagated-groups-comp-1")).thenReturn(Stream.of("A", "B"));
        when(client.patchGroupMembership(any(), eq("B"), eq("u1"), eq(false))).thenReturn(true);

        consumer.accept(client, ws);

        verify(client).patchGroupMembership(any(), eq("B"), eq("u1"), eq(false));
        verify(client).ensureGroupMembership(any(), eq("A"), eq("u1"));
        verify(user).setAttribute(eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 1 && l.contains("A")));
    }

    @Test
    void keepsFailedRemovalInStoredSet() {
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = mock(ScimClient.class);
        when(client.getComponentId()).thenReturn("comp-1");
        var groupA = group("A");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA));
        when(user.getAttributeStream("scim-propagated-groups-comp-1")).thenReturn(Stream.of("A", "B"));
        when(client.patchGroupMembership(any(), eq("B"), eq("u1"), eq(false))).thenReturn(false);

        consumer.accept(client, ws);

        verify(user).setAttribute(eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 2 && l.contains("A") && l.contains("B")));
    }

    @Test
    void noMembershipChangeEmitsNoRemoval() {
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = mock(ScimClient.class);
        when(client.getComponentId()).thenReturn("comp-1");
        var groupA = group("A");
        var groupB = group("B");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA, groupB));
        when(user.getAttributeStream("scim-propagated-groups-comp-1")).thenReturn(Stream.of("A", "B"));

        consumer.accept(client, ws);

        verify(client, never()).patchGroupMembership(any(), any(), eq("u1"), eq(false));
        verify(client).ensureGroupMembership(any(), eq("A"), eq("u1"));
        verify(client).ensureGroupMembership(any(), eq("B"), eq("u1"));
    }

    @Test
    void removesAttributeWhenNoGroupsRemain() {
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = mock(ScimClient.class);
        when(client.getComponentId()).thenReturn("comp-1");
        when(user.getGroupsStream()).thenReturn(Stream.empty());
        when(user.getAttributeStream("scim-propagated-groups-comp-1")).thenReturn(Stream.of("A"));
        when(client.patchGroupMembership(any(), eq("A"), eq("u1"), eq(false))).thenReturn(true);

        consumer.accept(client, ws);

        verify(client).patchGroupMembership(any(), eq("A"), eq("u1"), eq(false));
        verify(user).removeAttribute("scim-propagated-groups-comp-1");
        verify(client, never()).ensureGroupMembership(any(), any(), any());
    }

    @Test
    void successfulRemovalDropsWhileFailedRemovalIsKept() {
        // One import, two removals: B applies (drops from stored), C fails (stays).
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = mock(ScimClient.class);
        when(client.getComponentId()).thenReturn("comp-1");
        var groupA = group("A");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA));               // current = {A}
        when(user.getAttributeStream("scim-propagated-groups-comp-1"))
                .thenReturn(Stream.of("A", "B", "C"));                            // stored = {A,B,C}
        when(client.patchGroupMembership(any(), eq("B"), eq("u1"), eq(false))).thenReturn(true);
        when(client.patchGroupMembership(any(), eq("C"), eq("u1"), eq(false))).thenReturn(false);

        consumer.accept(client, ws);

        verify(client).patchGroupMembership(any(), eq("B"), eq("u1"), eq(false));
        verify(client).patchGroupMembership(any(), eq("C"), eq("u1"), eq(false));
        // next = current ∪ failed-removals = {A} ∪ {C}; B (applied) is dropped.
        verify(user).setAttribute(eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 2 && l.contains("A") && l.contains("C")));
    }
}
