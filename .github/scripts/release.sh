#!/usr/bin/env bash
# Release script for artifactory-gradle-plugin (main branch, Java 17).
# Runnable from GitHub Actions or directly on a developer machine.
#
# Expected env vars:
#   NEXT_VERSION                          - version to release (e.g. 6.2.1)
#   NEXT_DEVELOPMENT_VERSION               - next dev/snapshot version (e.g. 6.2.2-SNAPSHOT)
#   ARTIFACTORY_URL                        - Artifactory server URL
#   ARTIFACTORY_USER                       - Artifactory user
#   ARTIFACTORY_APIKEY                     - Artifactory API key
#   ORG_GRADLE_PROJECT_sonatypeUsername    - Sonatype/Maven Central username
#   ORG_GRADLE_PROJECT_sonatypePassword    - Sonatype/Maven Central password
#   MVN_CENTRAL_SIGNING_KEY                - base64-encoded GPG signing key
#   ORG_GRADLE_PROJECT_signingPassword     - GPG signing key passphrase
set -euo pipefail

git config user.name "JFrog CI"
git config user.email "eco-system@jfrog.com"

test -n "$NEXT_VERSION" -a "$NEXT_VERSION" != "0.0.0"
test -n "$NEXT_DEVELOPMENT_VERSION" -a "$NEXT_DEVELOPMENT_VERSION" != "0.0.x-SNAPSHOT"

jf c rm --quiet
jf c add internal --url=$ARTIFACTORY_URL --access-token=$ARTIFACTORY_APIKEY
jf gradlec --use-wrapper --repo-resolve ecosys-maven-remote --repo-deploy ecosys-oss-release-local --deploy-maven-desc

jf audit --exclusions "*node_modules*;*target*;*venv*;*test*;*functionalTest*"

sed -i -e "/version=/ s/=.*/=$NEXT_VERSION/" gradle.properties
git commit -am "[artifactory-release] Release version ${NEXT_VERSION} [skipRun]" --allow-empty
git tag ${NEXT_VERSION}

jf gradle clean validatePlugins build artifactoryPublish -x test -x functionalTest

jf rt bag && jf rt bce
jf rt bp

jf ds rbc ecosystem-artifactory-gradle-plugin $NEXT_VERSION --spec=./.jfrog-pipelines/release/specs/prod-rbc-filespec.json --spec-vars="version=$NEXT_VERSION" --sign
jf ds rbd ecosystem-artifactory-gradle-plugin $NEXT_VERSION --site="releases.jfrog.io" --sync

export ORG_GRADLE_PROJECT_signingKey=$(echo "$MVN_CENTRAL_SIGNING_KEY" | base64 -d)
./gradlew clean build publishToSonatype closeAndReleaseSonatypeStagingRepository -x test -x functionalTest -Psign

sed -i "s/\(version=\).*\$/\1${NEXT_DEVELOPMENT_VERSION}/" gradle.properties
git commit -am "[artifactory-release] Next development version [skipRun]"

git push
git push --tags
