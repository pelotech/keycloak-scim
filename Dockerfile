# syntax=docker/dockerfile:1.7

# Image is pure payload: a single shaded JAR at the root, intended to be
# mounted into a Keycloak container as a Kubernetes ImageVolume on
# /opt/keycloak/providers/. No shell, no entrypoint, no runtime — Keycloak
# loads providers from its own image; this one just contributes a file.
#
# Build context expects the JAR pre-staged at ./build/docker/keycloak-scim.jar
# (see the prepareDockerContext Gradle task).

FROM scratch

ARG VERSION=dev
ARG REVISION=unknown
ARG SOURCE=https://github.com/pelotech/keycloak-scim

LABEL org.opencontainers.image.title="keycloak-scim" \
      org.opencontainers.image.description="SCIM 2.0 outbound provisioning provider for Keycloak, with LDAP federation support and a reconciler for the LDAP-deletion gap (Keycloak #35235). Mount as an ImageVolume at /opt/keycloak/providers." \
      org.opencontainers.image.source="${SOURCE}" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="Apache-2.0"

COPY build/docker/keycloak-scim.jar /keycloak-scim.jar
