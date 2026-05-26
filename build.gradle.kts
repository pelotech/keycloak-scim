import java.time.Duration

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.openrewrite)
}

group = "sh.libre.scim"
version = "1.0.0" // x-release-please-version
description = "keycloak-scim"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

rewrite {
    activeRecipe(
        "org.openrewrite.staticanalysis.CodeCleanup",
        "org.openrewrite.staticanalysis.JavaApiBestPractices",
    )
    activeStyle("org.openrewrite.java.IntelliJ")
}

repositories {
    mavenCentral()
}

val integrationTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

val perfTest by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + integrationTest.output
    runtimeClasspath += sourceSets.main.get().output + integrationTest.output
}

configurations {
    testImplementation {
        extendsFrom(configurations.compileOnly.get())
    }
    named(integrationTest.implementationConfigurationName) {
        extendsFrom(configurations.testImplementation.get())
    }
    named(integrationTest.runtimeOnlyConfigurationName) {
        extendsFrom(configurations.testRuntimeOnly.get())
    }
    named(perfTest.implementationConfigurationName) {
        extendsFrom(configurations.getByName(integrationTest.implementationConfigurationName))
    }
    named(perfTest.runtimeOnlyConfigurationName) {
        extendsFrom(configurations.getByName(integrationTest.runtimeOnlyConfigurationName))
    }
}

dependencies {
    rewrite(platform(libs.openrewrite.recipe.bom))
    rewrite("org.openrewrite.recipe:rewrite-migrate-java")

    compileOnly(libs.keycloak.core)
    compileOnly(libs.keycloak.server.spi)
    compileOnly(libs.keycloak.server.spi.private)
    compileOnly(libs.keycloak.services)
    compileOnly(libs.keycloak.model.jpa)
    compileOnly(libs.keycloak.ldap.federation)
    compileOnly(libs.guava)

    implementation(libs.resilience4j.retry)
    implementation(libs.jakarta.ws.rs)
    implementation(libs.jakarta.persistence)
    implementation(libs.scim.sdk.common)
    implementation(libs.scim.sdk.client)
    implementation(libs.commons.lang3)

    testImplementation(libs.assertj)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.wiremock)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(integrationTest.implementationConfigurationName, libs.keycloak.admin.client)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.core)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.junit)
    add(integrationTest.implementationConfigurationName, libs.testcontainers.keycloak)
    add(integrationTest.implementationConfigurationName, libs.wiremock)
    add(integrationTest.implementationConfigurationName, libs.awaitility)
    add(integrationTest.implementationConfigurationName, libs.nimbus.jose.jwt)
    add(integrationTest.runtimeOnlyConfigurationName, libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    dependsOn(tasks.named("shadowJar"))
    val shadowJarTask = tasks.named<Jar>("shadowJar")
    // docker-java ships with a default API version (1.32) that modern Docker
    // Engines reject. Pin to a version that all currently-supported engines accept.
    systemProperty("api.version", "1.43")
    // Each test class boots its own Keycloak + OpenLDAP + WireMock stack.
    // Run sequentially (maxParallelForks=1) and fork a fresh JVM per class
    // (forkEvery=1) so Testcontainers' static container fields and Ryuk
    // shutdown hooks reset cleanly between classes.
    maxParallelForks = 1
    forkEvery = 1
    // Forward the Keycloak image override (used by the CI matrix) and
    // any other -D properties whose names we deliberately accept.
    listOf("keycloak.image").forEach { prop ->
        System.getProperty(prop)?.let { systemProperty(prop, it) }
    }
    doFirst {
        systemProperty("keycloak.plugin.jar", shadowJarTask.get().archiveFile.get().asFile.absolutePath)
    }
}

// Performance/scale tests. Deliberately NOT part of `check` — they run for
// many minutes, allocate larger Keycloak heaps, and are intended for ad-hoc
// invocation when measuring or re-measuring. Invoke with `./gradlew
// performanceTest`. Reports land under build/reports/perf/.
tasks.register<Test>("performanceTest") {
    description = "Runs scale/perf tests against a real Keycloak + LDAP + SCIM stack."
    group = "verification"
    testClassesDirs = perfTest.output.classesDirs
    classpath = perfTest.runtimeClasspath
    dependsOn(tasks.named("shadowJar"))
    val shadowJarTask = tasks.named<Jar>("shadowJar")
    systemProperty("api.version", "1.43")
    // Each perf test class spins up its own stack; sequential per-class JVMs
    // for the same reasons as integrationTest.
    maxParallelForks = 1
    forkEvery = 1
    // Generous default; individual scenarios can stretch toward this when
    // exercising large user cohorts.
    timeout.set(Duration.ofMinutes(30))
    // Forward selected -D system properties from the gradle invocation to
    // the test JVM. Without this, `-Dperf.userCount=10000` would be set on
    // the gradle daemon but invisible to Integer.getInteger() inside tests.
    listOf("perf.userCount", "perf.scimSinkLatencyMs").forEach { prop ->
        System.getProperty(prop)?.let { systemProperty(prop, it) }
    }
    // Always re-run perf tests (their inputs are wall-clock-sensitive,
    // not source-driven, and gradle's UP-TO-DATE check would otherwise
    // skip a re-measurement).
    outputs.upToDateWhen { false }
    doFirst {
        systemProperty("keycloak.plugin.jar", shadowJarTask.get().archiveFile.get().asFile.absolutePath)
        systemProperty("perf.report.dir", layout.buildDirectory.dir("reports/perf").get().asFile.absolutePath)
    }
}

tasks.check {
    dependsOn("integrationTest")
}

// Reproducible archives: identical inputs produce identical outputs (same
// JAR sha256 across builds). Drops file mtimes inside archives and pins
// entry order. Trade-off is purely cosmetic (mtimes inside the JAR no
// longer reflect "when this was built"); for an artifact pinned by digest
// in production, that's a feature, not a loss.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Stages the shaded JAR under build/docker/ with a stable, version-less
// filename. Operators reference the version via the OCI image tag — no
// need to repeat it in the path inside the image.
tasks.register<Copy>("prepareDockerContext") {
    description = "Stages the shaded JAR for Docker image build."
    group = "distribution"
    dependsOn(tasks.named("shadowJar"))
    from(tasks.named<Jar>("shadowJar"))
    rename { "keycloak-scim.jar" }
    into(layout.buildDirectory.dir("docker"))
}
