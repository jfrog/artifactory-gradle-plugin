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
# Optional env vars:
#   GH_COM_TOKEN                           - GitHub token with push access to this repo. If unset, a token embedded in
#                                            the 'origin' URL (https://TOKEN@github.com/...) is used. If neither is
#                                            present, the git credentials already configured on the machine are used.
set -Eeuo pipefail

# ---------------------------------------------------------------------------------------------------------------------
# Failure handling. The release is NOT atomic: once artifacts are published (Artifactory, release bundle, Maven
# Central) they cannot be taken back, so a failure at a late stage must NOT be handled by simply re-running the job.
# STAGE records how far we got so the message below tells the operator exactly what is safe to do.
# ---------------------------------------------------------------------------------------------------------------------
STAGE="preflight"
START_SHA=""
on_error() {
  local rc=$?
  trap - ERR
  {
    echo ""
    echo "================================================================================"
    echo "RELEASE ${NEXT_VERSION:-?} FAILED during stage: ${STAGE} (exit code ${rc})"
    case "$STAGE" in
      preflight|audit|local-commit)
        echo "Nothing has been published yet. Fix the problem and re-run the release."
        ;;
      artifactory-publish)
        echo "Artifactory upload was in progress: some ${NEXT_VERSION} files may already be in ecosys-oss-release-local."
        echo "Nothing was sent to Maven Central or releases.jfrog.io. Remove the partial ${NEXT_VERSION} files from"
        echo "ecosys-oss-release-local before re-running, otherwise the re-run may fail or overwrite them."
        ;;
      release-bundle)
        echo "Already published: Artifactory ${NEXT_VERSION} + build info. Nothing was sent to Maven Central."
        echo "Check whether release bundle ecosystem-artifactory-gradle-plugin/${NEXT_VERSION} exists before re-running."
        ;;
      maven-central)
        echo "Already published: Artifactory ${NEXT_VERSION}, build info and the releases.jfrog.io bundle."
        echo "Maven Central may have a staging repo or a released ${NEXT_VERSION}. Check Maven Central / Sonatype before"
        echo "re-running; a second upload of the same version is rejected."
        ;;
      git-push)
        echo "EVERYTHING IS PUBLISHED (Artifactory, releases.jfrog.io, Maven Central). Only the git push failed."
        echo "DO NOT RE-RUN THIS JOB. The ${NEXT_VERSION} tag and release commits are missing from GitHub, and the"
        echo "'Publish to Gradle Gallery' workflow needs the ${NEXT_VERSION} tag. Recreate them by hand from ${START_SHA:-the main commit this run started from}:"
        echo "  1. set 'version=${NEXT_VERSION}' in gradle.properties, commit '[artifactory-release] Release version ${NEXT_VERSION} [skipRun]'"
        echo "  2. git tag ${NEXT_VERSION}"
        echo "  3. set 'version=${NEXT_DEVELOPMENT_VERSION}' in gradle.properties, commit '[artifactory-release] Next development version [skipRun]'"
        echo "  4. git push origin HEAD:main ${NEXT_VERSION}"
        ;;
    esac
    echo "================================================================================"
  } >&2
  exit "$rc"
}
trap on_error ERR

git config user.name "JFrog CI"
git config user.email "eco-system@jfrog.com"

test -n "$NEXT_VERSION" -a "$NEXT_VERSION" != "0.0.0"
test -n "$NEXT_DEVELOPMENT_VERSION" -a "$NEXT_DEVELOPMENT_VERSION" != "0.0.x-SNAPSHOT"

# --- Git authentication ----------------------------------------------------------------------------------------------
# Do not rely on a token embedded in the remote URL: git drops URL credentials when it follows a redirect, which made
# the final 'git push' fail with "could not read Username" after everything had already been published. Instead keep the
# remote URL token-free and answer git's credential request through a helper that reads the token from the environment
# (never written to disk), so it works regardless of redirects.
export GIT_TERMINAL_PROMPT=0
GIT_PUSH_TOKEN="${GH_COM_TOKEN:-}"
ORIGIN_URL="$(git remote get-url origin)"
if [[ "$ORIGIN_URL" =~ ^https://([^/@]+)@(github\.com/.*)$ ]]; then
  [ -n "$GIT_PUSH_TOKEN" ] || GIT_PUSH_TOKEN="${BASH_REMATCH[1]#*:}"
  git remote set-url origin "https://${BASH_REMATCH[2]}"
fi
if [ -n "$GIT_PUSH_TOKEN" ]; then
  export GIT_PUSH_TOKEN
  git config --local credential.helper '!f() { test "$1" = get && printf "username=x-access-token\npassword=%s\n" "$GIT_PUSH_TOKEN"; }; f'
fi

# --- Preflight: fail now, before anything irreversible is published ---------------------------------------------------
START_SHA="$(git rev-parse HEAD)"
echo "Preflight: verifying push access to origin (dry run)..."
git push --dry-run origin HEAD > /dev/null
if git ls-remote --exit-code --tags origin "refs/tags/${NEXT_VERSION}" > /dev/null 2>&1; then
  echo "Preflight failed: tag ${NEXT_VERSION} already exists on origin. Was ${NEXT_VERSION} already released?" >&2
  false
fi
if curl -fsIL -o /dev/null "https://repo1.maven.org/maven2/org/jfrog/buildinfo/build-info-extractor-gradle/${NEXT_VERSION}/build-info-extractor-gradle-${NEXT_VERSION}.pom"; then
  echo "Preflight failed: ${NEXT_VERSION} is already on Maven Central." >&2
  false
fi
echo "Preflight OK."
STAGE="audit"

jf c rm --quiet
jf c add internal --url=$ARTIFACTORY_URL --user=$ARTIFACTORY_USER --access-token=$ARTIFACTORY_APIKEY
jf gradlec --use-wrapper --repo-resolve ecosys-maven-remote --repo-deploy ecosys-oss-release-local --deploy-maven-desc

jf audit --exclusions "*node_modules*;*target*;*venv*;*test*;*functionalTest*"

STAGE="local-commit"
sed -i -e "/version=/ s/=.*/=$NEXT_VERSION/" gradle.properties
git commit -am "[artifactory-release] Release version ${NEXT_VERSION} [skipRun]" --allow-empty
git tag ${NEXT_VERSION}

STAGE="artifactory-publish"
jf gradle clean validatePlugins build artifactoryPublish -x test -x functionalTest

jf rt bag && jf rt bce
jf rt bp

STAGE="release-bundle"
jf ds rbc ecosystem-artifactory-gradle-plugin $NEXT_VERSION --spec=./.jfrog-pipelines/release/specs/prod-rbc-filespec.json --spec-vars="version=$NEXT_VERSION" --sign
jf ds rbd ecosystem-artifactory-gradle-plugin $NEXT_VERSION --site="releases.jfrog.io" --sync

STAGE="maven-central"
export ORG_GRADLE_PROJECT_signingKey=$(echo "$MVN_CENTRAL_SIGNING_KEY" | base64 -d)
./gradlew clean build publishToSonatype closeAndReleaseSonatypeStagingRepository -x test -x functionalTest -Psign

sed -i "s/\(version=\).*\$/\1${NEXT_DEVELOPMENT_VERSION}/" gradle.properties
git commit -am "[artifactory-release] Next development version [skipRun]"

STAGE="git-push"
git push
git push --tags
STAGE="done"
