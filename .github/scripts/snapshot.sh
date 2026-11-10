#!/usr/bin/env bash
# Snapshot script for artifactory-gradle-plugin (main branch, Java 17).
# Runnable from GitHub Actions or directly on a developer machine.
#
# Expected env vars:
#   ARTIFACTORY_URL       - Artifactory server URL
#   ARTIFACTORY_USER      - Artifactory user
#   ARTIFACTORY_APIKEY    - Artifactory API key
#   JFROG_CLI_BUILD_NUMBER - build number (job-level env, equals ${{ github.run_number }} in CI)
set -euo pipefail

jf c rm --quiet
jf c add internal --url=$ARTIFACTORY_URL --user=$ARTIFACTORY_USER --password=$ARTIFACTORY_APIKEY
jf gradlec --use-wrapper --repo-resolve ecosys-maven-remote --repo-deploy ecosys-oss-snapshot-local --deploy-maven-desc

jf audit --fail=false --exclusions "*node_modules*;*target*;*venv*;*test*;*functionalTest*"

jf rt del "ecosys-oss-snapshot-local/com/jfrog/buildinfo/build-info-extractor-gradle/*" --quiet

jf gradle clean validatePlugins build artifactoryPublish -x test -x functionalTest

jf rt bag && jf rt bce
jf rt bp

jf ds rbc ecosystem-artifactory_gradle_plugin-snapshot $JFROG_CLI_BUILD_NUMBER --spec=./.jfrog-pipelines/release/specs/dev-rbc-filespec.json --sign
jf ds rbd ecosystem-artifactory_gradle_plugin-snapshot $JFROG_CLI_BUILD_NUMBER --site="releases.jfrog.io" --sync
