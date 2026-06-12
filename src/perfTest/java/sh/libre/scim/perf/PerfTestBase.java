package sh.libre.scim.perf;

import sh.libre.scim.integration.IntegrationTestBase;

import javax.naming.NamingException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds bulk LDAP-seeding and metric-capture scaffolding on top of
 * {@link IntegrationTestBase}. Subclasses get the same Keycloak +
 * OpenLDAP + WireMock stack but with helpers for generating thousands of
 * users and their group memberships, plus a {@link PerfReport} writer.
 *
 * <p>Note on container sizing: at 10k users a default Keycloak container
 * heap can run tight. The {@code KeycloakContainer} we inherit from
 * {@code IntegrationTestBase} doesn't expose a heap-size setter directly,
 * but Keycloak respects {@code JAVA_OPTS_KC_HEAP} via env. If a perf run
 * reports OOM, lift the heap there.
 */
abstract class PerfTestBase extends IntegrationTestBase {

    /**
     * Bulk-add N inetOrgPerson entries under {@code ou=users,dc=test,dc=local}.
     * Returns the list of usernames created (uid=u<N>).
     */
    protected List<String> seedLdapUsers(int count) throws NamingException {
        return seedLdapUsers("u", count);
    }

    /**
     * Creates a groupOfNames entry under {@code ou=groups,dc=test,dc=local} with
     * the given member DNs. Assumes the {@code ou=groups} OU already exists in
     * the LDIF; if not, the caller should add it first.
     */
    protected void seedLdapGroup(String cn, List<String> memberDns) throws NamingException {
        var ctx = new InitialDirContext(newLdapEnv());
        try {
            var attrs = new BasicAttributes();
            var oc = new BasicAttribute("objectClass");
            oc.add("groupOfNames");
            oc.add("top");
            attrs.put(oc);
            attrs.put("cn", cn);
            var members = new BasicAttribute("member");
            for (String dn : memberDns) {
                members.add(dn);
            }
            attrs.put(members);
            ctx.createSubcontext("cn=" + cn + ",ou=groups,dc=test,dc=local", attrs);
        } finally {
            ctx.close();
        }
    }

    /** Hard-deletes seeded entries. Caller passes in DNs to remove. Errors are swallowed. */
    protected void cleanupLdapEntries(List<String> dns) {
        try {
            var ctx = new InitialDirContext(newLdapEnv());
            try {
                for (String dn : dns) {
                    try {
                        ctx.destroySubcontext(dn);
                    } catch (NamingException ignored) {
                        // best-effort
                    }
                }
            } finally {
                ctx.close();
            }
        } catch (NamingException ignored) {
            // best-effort
        }
    }
}
