package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts what the shaded provider JAR ships. Boots no containers — it only
 * inspects the artifact — but lives in this source set because that is where
 * the built JAR's path is wired in, via the keycloak.plugin.jar property.
 *
 * The rule so far is that BouncyCastle stays out. scim-sdk-client declares
 * bcprov/bcpkix for keystore-based mTLS, which we never configure, so
 * build.gradle.kts excludes them; bundling them puts org.bouncycastle.* in the
 * same packages as bc-fips on FIPS Keycloak images. A scim-sdk bump that starts
 * using BouncyCastle for real would otherwise reintroduce that on upgrade.
 *
 * To run: ./gradlew integrationTest
 */
class ShadedJarContentsIT {

    private static final File PLUGIN_JAR = new File(
        System.getProperty(
            "keycloak.plugin.jar",
            "build/docker/keycloak-scim.jar"
        )
    );

    @Test
    void shadedJarBundlesNoBouncyCastle() throws Exception {
        // contains() rather than startsWith(): BouncyCastle is a multi-release
        // JAR, so the classes appear under META-INF/versions/9/ as well.
        List<String> offenders = jarEntryNames(name -> name.contains("org/bouncycastle/"));

        assertTrue(
            offenders.isEmpty(),
            "shaded JAR must not bundle BouncyCastle (splits packages with bc-fips on "
                + "FIPS Keycloak images); found " + offenders.size() + " entries, e.g. "
                + offenders.subList(0, Math.min(5, offenders.size()))
        );
    }

    @Test
    void shadedJarStillBundlesTheScimSdk() throws Exception {
        // Without this, an empty or mis-built JAR would satisfy the exclusion
        // assertion above.
        assertTrue(
            !jarEntryNames(name -> name.startsWith("de/captaingoldfish/scim/sdk/client/")).isEmpty(),
            "shaded JAR should still bundle scim-sdk-client"
        );
    }

    private static List<String> jarEntryNames(Predicate<String> filter) throws Exception {
        try (var jar = new JarFile(PLUGIN_JAR)) {
            return jar.stream().map(JarEntry::getName).filter(filter).toList();
        }
    }
}
