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
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.storage.federated.UserFederatedStorageProvider;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    @Mock UserFederatedStorageProvider fed;

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
        when(ws.getProvider(UserFederatedStorageProvider.class)).thenReturn(fed);
        return ws;
    }

    private GroupModel group(String id) {
        var g = mock(GroupModel.class);
        when(g.getId()).thenReturn(id);
        return g;
    }

    /** A mock client for the default delta path (group-patchOp=true). */
    private ScimClient deltaClient() {
        var client = mock(ScimClient.class);
        when(client.getComponentId()).thenReturn("comp-1");
        when(client.isGroupMembershipDeltaEnabled()).thenReturn(true);
        return client;
    }

    /** Federated-storage attributes map holding the propagated-group set (empty if no ids). */
    private MultivaluedHashMap<String, String> storedGroups(String... ids) {
        var m = new MultivaluedHashMap<String, String>();
        if (ids.length > 0) {
            m.put("scim-propagated-groups-comp-1", new ArrayList<>(List.of(ids)));
        }
        return m;
    }

    @Test
    void removesGroupsTheUserHasLeft() {
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = deltaClient();
        var groupA = group("A");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA));
        when(fed.getAttributes(any(), eq("u1"))).thenReturn(storedGroups("A", "B"));
        when(client.patchGroupMembership(any(), eq("B"), eq("u1"), eq(false))).thenReturn(true);

        consumer.accept(client, ws);

        verify(client).patchGroupMembership(any(), eq("B"), eq("u1"), eq(false));
        // A is already propagated (in stored) -> NOT re-added (delta).
        verify(client, never()).ensureGroupMembership(any(), any(), any());
        verify(fed).setAttribute(any(), eq("u1"), eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 1 && l.contains("A")));
    }

    @Test
    void keepsFailedRemovalInStoredSet() {
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = deltaClient();
        var groupA = group("A");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA));
        when(fed.getAttributes(any(), eq("u1"))).thenReturn(storedGroups("A", "B"));
        when(client.patchGroupMembership(any(), eq("B"), eq("u1"), eq(false))).thenReturn(false);

        consumer.accept(client, ws);

        verify(fed).setAttribute(any(), eq("u1"), eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 2 && l.contains("A") && l.contains("B")));
    }

    @Test
    void noMembershipChangeEmitsNoScimCalls() {
        // Steady state: every group already propagated. This is the common case —
        // a full sync re-fires the import hook for unchanged users — and must send
        // ZERO SCIM PATCHes (Follow-up A: no redundant per-sync re-assertion).
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = deltaClient();
        var groupA = group("A");
        var groupB = group("B");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA, groupB));
        when(fed.getAttributes(any(), eq("u1"))).thenReturn(storedGroups("A", "B"));

        consumer.accept(client, ws);

        verify(client, never()).patchGroupMembership(any(), any(), eq("u1"), eq(false));
        verify(client, never()).ensureGroupMembership(any(), any(), any());
    }

    @Test
    void removesAttributeWhenNoGroupsRemain() {
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = deltaClient();
        when(user.getGroupsStream()).thenReturn(Stream.empty());
        when(fed.getAttributes(any(), eq("u1"))).thenReturn(storedGroups("A"));
        when(client.patchGroupMembership(any(), eq("A"), eq("u1"), eq(false))).thenReturn(true);

        consumer.accept(client, ws);

        verify(client).patchGroupMembership(any(), eq("A"), eq("u1"), eq(false));
        verify(fed).removeAttribute(any(), eq("u1"), eq("scim-propagated-groups-comp-1"));
        verify(client, never()).ensureGroupMembership(any(), any(), any());
    }

    @Test
    void successfulRemovalDropsWhileFailedRemovalIsKept() {
        // One import, two removals: B applies (drops from stored), C fails (stays).
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = deltaClient();
        var groupA = group("A");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA));               // current = {A}
        when(fed.getAttributes(any(), eq("u1")))
                .thenReturn(storedGroups("A", "B", "C"));                         // stored = {A,B,C}
        when(client.patchGroupMembership(any(), eq("B"), eq("u1"), eq(false))).thenReturn(true);
        when(client.patchGroupMembership(any(), eq("C"), eq("u1"), eq(false))).thenReturn(false);

        consumer.accept(client, ws);

        verify(client).patchGroupMembership(any(), eq("B"), eq("u1"), eq(false));
        verify(client).patchGroupMembership(any(), eq("C"), eq("u1"), eq(false));
        // next = current ∪ failed-removals = {A} ∪ {C}; B (applied) is dropped.
        verify(fed).setAttribute(any(), eq("u1"), eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 2 && l.contains("A") && l.contains("C")));
    }

    @Test
    void firstImportRecordsCurrentWithNoRemovals() {
        // No prior bookkeeping (the common first-import path): additions only,
        // nothing to remove, and the current set is recorded.
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = deltaClient();
        var groupA = group("A");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA));            // current = {A}
        when(fed.getAttributes(any(), eq("u1"))).thenReturn(storedGroups());  // stored = {} (empty)
        when(client.ensureGroupMembership(any(), eq("A"), eq("u1"))).thenReturn(true);

        consumer.accept(client, ws);

        verify(client, never()).patchGroupMembership(any(), any(), eq("u1"), eq(false));
        verify(client).ensureGroupMembership(any(), eq("A"), eq("u1"));
        verify(fed).setAttribute(any(), eq("u1"), eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 1 && l.contains("A")));
    }

    @Test
    void addsOnlyTheGroupsNotAlreadyPropagated() {
        // current = {A,B}, stored = {A}: A is already propagated, only B is added.
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = deltaClient();
        var groupA = group("A");
        var groupB = group("B");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA, groupB));
        when(fed.getAttributes(any(), eq("u1"))).thenReturn(storedGroups("A"));
        when(client.ensureGroupMembership(any(), eq("B"), eq("u1"))).thenReturn(true);

        consumer.accept(client, ws);

        verify(client).ensureGroupMembership(any(), eq("B"), eq("u1"));        // added
        verify(client, never()).ensureGroupMembership(any(), eq("A"), eq("u1")); // already propagated
        verify(fed).setAttribute(any(), eq("u1"), eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 2 && l.contains("A") && l.contains("B")));
    }

    @Test
    void failedAddIsNotRecordedSoItRetries() {
        // current = {A,B}, stored = {B}: A is newly added but fails -> not recorded;
        // B stays propagated. next = {B} only, so A is re-attempted next import.
        var consumer = captureGroupConsumer("u1");
        var ws = workerSessionReturning("u1");
        var client = deltaClient();
        var groupA = group("A");
        var groupB = group("B");
        when(user.getGroupsStream()).thenReturn(Stream.of(groupA, groupB));
        when(fed.getAttributes(any(), eq("u1"))).thenReturn(storedGroups("B"));
        when(client.ensureGroupMembership(any(), eq("A"), eq("u1"))).thenReturn(false);

        consumer.accept(client, ws);

        verify(client).ensureGroupMembership(any(), eq("A"), eq("u1"));
        verify(fed).setAttribute(any(), eq("u1"), eq("scim-propagated-groups-comp-1"),
                argThat(l -> l.size() == 1 && l.contains("B")));
    }

    @Test
    void skipsEntirelyWhenGroupPatchOpDisabled() {
        // group-patchOp=false: add/remove would fall back to a full-group
        // `replace` that re-imports the federated group's members (loop). The
        // worker must do nothing at all — not even re-fetch the user.
        var consumer = captureGroupConsumer("u1");
        var ws = mock(KeycloakSession.class);
        var client = mock(ScimClient.class);
        when(client.isGroupMembershipDeltaEnabled()).thenReturn(false);

        consumer.accept(client, ws);

        verify(client, never()).patchGroupMembership(any(), any(), any(), anyBoolean());
        verify(client, never()).ensureGroupMembership(any(), any(), any());
        verify(ws, never()).users();
    }
}
