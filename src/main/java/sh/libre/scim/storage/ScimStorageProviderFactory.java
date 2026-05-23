package sh.libre.scim.storage;

import java.net.URI;
import java.util.Date;
import java.util.List;

import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProviderFactory;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.user.ImportSynchronization;
import org.keycloak.storage.user.SynchronizationResult;

import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.core.OAuthClientCredentialsTokenSource;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;
import sh.libre.scim.reconcile.ReconcilerConfigValidator;
import sh.libre.scim.reconcile.ReconcilerScheduler;

import de.captaingoldfish.scim.sdk.common.constants.HttpHeader;

public class ScimStorageProviderFactory
        implements UserStorageProviderFactory<ScimStorageProvider>, ImportSynchronization {
    final private Logger LOGGER = Logger.getLogger(ScimStorageProviderFactory.class);
    public final static String ID = "scim";

    public static final String RECONCILER_ENABLED = "reconciler-enabled";
    public static final String RECONCILER_INTERVAL_SECONDS = "reconciler-interval-seconds";
    public static final String RECONCILER_STALE_THRESHOLD_SECONDS = "reconciler-stale-threshold-seconds";

    public static String reconcilerTaskName(String componentId) {
        return "scim-reconciler-" + componentId;
    }

    protected static final List<ProviderConfigProperty> configMetadata;
    static {
        configMetadata = ProviderConfigurationBuilder.create()
                .property()
                .name("endpoint")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("SCIM 2.0 endpoint")
                .helpText("""
                        External SCIM 2.0 base \
                        URL (/ServiceProviderConfig  /Schemas and /ResourcesTypes should be accessible)\
                        """)
                .add()
                .property()
                .name("content-type")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("Endpoint content type")
                .helpText("Only used when endpoint doesn't support application/scim+json")
                .options(MediaType.APPLICATION_JSON.toString(), HttpHeader.SCIM_CONTENT_TYPE)
                .defaultValue(HttpHeader.SCIM_CONTENT_TYPE)
                .add()
                .property()
                .name("auth-mode")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("Auth mode")
                .helpText("Select the authorization mode")
                .options("NONE", "BASIC_AUTH", "BEARER", "CLIENT_CREDENTIALS")
                .defaultValue("NONE")
                .add()
                .property()
                .name("auth-user")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Auth username")
                .helpText("Required for basic authentification.")
                .add()
                .property()
                .name("auth-pass")
                .type(ProviderConfigProperty.PASSWORD)
                .label("Auth password/token")
                .helpText("Password or token required for basic or bearer authentification.")
                .add()
                .property()
                .name("oauth-client-id")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("OAuth client ID")
                .helpText("Required when auth-mode is CLIENT_CREDENTIALS.")
                .add()
                .property()
                .name("oauth-client-secret")
                .type(ProviderConfigProperty.PASSWORD)
                .label("OAuth client secret")
                .helpText("Required when auth-mode is CLIENT_CREDENTIALS. Stored via Keycloak Vault Provider where configured.")
                .add()
                .property()
                .name("oauth-token-endpoint")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("OAuth token endpoint")
                .helpText("Full URL of the OAuth 2.0 token endpoint. Required when auth-mode is CLIENT_CREDENTIALS.")
                .add()
                .property()
                .name("oauth-scope")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("OAuth scope")
                .helpText("Optional space-separated OAuth scopes. Sent as the 'scope' parameter on the token request when set; omitted when blank.")
                .add()
                .property()
                .name("propagation-user")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable user propagation")
                .defaultValue("true")
                .add()
                .property()
                .name("propagation-group")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable group propagation")
                .defaultValue("true")
                .add()
                .property()
                .name("sync-import")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable import during sync")
                .add()
                .property()
                .name("sync-import-action")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("Import action")
                .helpText("What to do when the user don\'t exists in Keycloak.")
                .options("NOTHING", "CREATE_LOCAL", "DELETE_REMOTE")
                .defaultValue("CREATE_LOCAL")
                .add()
                .property()
                .name("sync-refresh")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable refresh during sync")
                .add()
                .property()
                .name("group-patchOp")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Use PATCH for groups")
                .helpText("When enabled, use PATCH operations for groups. When disabled, use PUT (with automatic PATCH fallback on 405).")
                .defaultValue(false)
                .add()
                .property()
                .name("user-patchOp")
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Use PATCH for users")
                .helpText("When enabled, use PATCH operations for users. When disabled, use PUT.")
                .defaultValue(false)
                .add()
                .property()
                .name("username-source")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("Username source")
                .helpText("The user attribute to use as the SCIM userName field.")
                .options("username", "email")
                .defaultValue("username")
                .add()
                .property()
                .name("group-filter")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Group filter patterns")
                .helpText("Comma-separated regex patterns for group names to sync (e.g. 'admins,team-.*'). Leave empty to sync all groups.")
                .add()
                .property()
                .name(RECONCILER_ENABLED)
                .type(ProviderConfigProperty.BOOLEAN_TYPE)
                .label("Enable LDAP-deletion reconciler")
                .helpText("When enabled, a periodic task issues SCIM DELETE for mapped users whose local UserModel is gone or whose 'ldap-federation-last-seen' attribute is older than the stale threshold. Opt-in because it's a workaround for upstream Keycloak #35235.")
                .defaultValue(false)
                .add()
                .property()
                .name(RECONCILER_INTERVAL_SECONDS)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Reconciler interval (seconds)")
                .helpText("How often the reconciler task runs, in seconds. Default 86400 (24h).")
                .defaultValue("86400")
                .add()
                .property()
                .name(RECONCILER_STALE_THRESHOLD_SECONDS)
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("Stale threshold (seconds)")
                .helpText("Users whose 'ldap-federation-last-seen' attribute is older than this are considered absent. Must be larger than the federation's periodic sync period. Default 172800 (48h).")
                .defaultValue("172800")
                .add()
                .build();
    }

    @Override
    public ScimStorageProvider create(KeycloakSession session, ComponentModel model) {
        LOGGER.info("create");
        return new ScimStorageProvider();
    }

    @Override
    public void onCreate(KeycloakSession session, org.keycloak.models.RealmModel realm, ComponentModel model) {
        ReconcilerScheduler.scheduleIfEnabled(
            session.getKeycloakSessionFactory(), session, realm.getId(), model);
    }

    @Override
    public void onUpdate(KeycloakSession session, org.keycloak.models.RealmModel realm,
                         ComponentModel oldModel, ComponentModel newModel) {
        OAuthClientCredentialsTokenSource.invalidate(newModel.getId());
        ReconcilerScheduler.scheduleIfEnabled(
            session.getKeycloakSessionFactory(), session, realm.getId(), newModel);
    }

    @Override
    public void preRemove(KeycloakSession session, org.keycloak.models.RealmModel realm, ComponentModel model) {
        OAuthClientCredentialsTokenSource.invalidate(model.getId());
        ReconcilerScheduler.cancel(session, model);
    }

    @Override
    public void validateConfiguration(KeycloakSession session, org.keycloak.models.RealmModel realm,
                                      ComponentModel model)
            throws org.keycloak.component.ComponentValidationException {
        var ldapFederations = realm.getComponentsStream()
            .filter(c -> "ldap".equals(c.getProviderId()))
            .toList();
        ReconcilerConfigValidator.validate(model, ldapFederations);

        String authMode = model.get("auth-mode");
        if ("CLIENT_CREDENTIALS".equals(authMode)) {
            requireNonBlank(model, "oauth-client-id");
            requireNonBlank(model, "oauth-client-secret");
            String endpoint = requireNonBlank(model, "oauth-token-endpoint");
            try {
                URI uri = URI.create(endpoint);
                if (uri.getScheme() == null
                    || (!uri.getScheme().equals("http") && !uri.getScheme().equals("https"))) {
                    throw new ComponentValidationException(
                        "oauth-token-endpoint must be an absolute http(s) URL");
                }
            } catch (IllegalArgumentException e) {
                throw new ComponentValidationException(
                    "oauth-token-endpoint must be an absolute http(s) URL", e);
            }
        }
    }

    private static String requireNonBlank(ComponentModel m, String name) {
        String v = m.get(name);
        if (v == null || v.isBlank()) {
            throw new ComponentValidationException(
                name + " is required when auth-mode is CLIENT_CREDENTIALS");
        }
        return v;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configMetadata;
    }

    @Override
    public SynchronizationResult sync(KeycloakSessionFactory sessionFactory, String realmId,
            UserStorageProviderModel model) {
        LOGGER.info("sync");
        var result = new SynchronizationResult();
        KeycloakModelUtils.runJobInTransaction(sessionFactory, new KeycloakSessionTask() {

            @Override
            public void run(KeycloakSession session) {
                var realm = session.realms().getRealm(realmId);
                session.getContext().setRealm(realm);
                try (var dispatcher = new ScimDispatcher(session)) {
                    if ("true".equals(model.get("propagation-user"))) {
                        dispatcher.runOne(model, client -> client.sync(UserAdapter.class, result));
                    }
                    if ("true".equals(model.get("propagation-group"))) {
                        dispatcher.runOne(model, client -> client.sync(GroupAdapter.class, result));
                    }
                }
            }

        });

        return result;

    }

    @Override
    public SynchronizationResult syncSince(Date lastSync, KeycloakSessionFactory sessionFactory, String realmId,
            UserStorageProviderModel model) {
        return this.sync(sessionFactory, realmId, model);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Boot-time scan: schedule timers for components already configured
        // across all realms. The runtime entry points for newly-added or
        // updated components are onCreate / onUpdate; postInit only handles
        // the case where Keycloak restarts with components already in place.
        //
        // Wrapped in try/catch because postInit on factories runs before the
        // JPA layer is fully initialized in some startup orderings, and
        // session.realms() will throw if it's called too early. A failed scan
        // here is recoverable — onCreate / onUpdate / next-restart will pick
        // things up — so we log and continue rather than abort startup.
        try {
            KeycloakModelUtils.runJobInTransaction(factory, session ->
                session.realms().getRealmsStream().forEach(realm ->
                    realm.getComponentsStream()
                        .filter(c -> ID.equals(c.getProviderId()))
                        .forEach(c -> ReconcilerScheduler.scheduleIfEnabled(
                            factory, session, realm.getId(), c))
                )
            );
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Reconciler boot-time scan failed; will rely on onCreate/onUpdate/next-restart to schedule");
        }
    }

}
