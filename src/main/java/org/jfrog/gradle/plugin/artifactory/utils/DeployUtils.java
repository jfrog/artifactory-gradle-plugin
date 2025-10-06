package org.jfrog.gradle.plugin.artifactory.utils;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.client.ArtifactoryUploadResponse;
import org.jfrog.build.extractor.Proxy;
import org.jfrog.build.extractor.ProxySelector;
import org.jfrog.build.extractor.ci.BuildInfo;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.IncludeExcludePatterns;
import org.jfrog.build.extractor.clientConfiguration.PatternMatcher;
import org.jfrog.build.extractor.clientConfiguration.client.artifactory.ArtifactoryManager;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployableArtifactsUtils;
import org.jfrog.build.extractor.retention.Utils;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleDeployDetails;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class DeployUtils {

    private static final Logger log = Logging.getLogger(DeployUtils.class);

    /**
     * Deploy all the artifacts from a given ArtifactoryTask base on a given arguments.
     * Populate a given set with the details that were deployed
     *
     * @param accRoot          - client configurations to apply in deployment
     * @param propsRoot        - root properties to merge with the task properties
     * @param allDeployDetails - a container that will be populated with the details of the deployed artifacts
     * @param artifactoryTask  - the task to deploy its details.
     * @param logPrefix        - the in case the deployment is in multi-threads a prefix to each log
     */
    public static void deployTaskArtifacts(ArtifactoryClientConfiguration accRoot, Map<String, String> propsRoot, Map<String,
            Set<DeployDetails>> allDeployDetails, ArtifactoryTask artifactoryTask, String logPrefix) {
        try {
            if (!artifactoryTask.getDidWork()) {
                log.debug("Task '{}' did no work", artifactoryTask.getPath());
                return;
            }
            if (artifactoryTask.getDeployDetails().isEmpty()) {
                log.debug("Task '{}' has nothing to deploy", artifactoryTask.getPath());
                return;
            }
            ArtifactoryClientConfiguration.PublisherHandler taskPublisher = ExtensionsUtils.getPublisherHandler(artifactoryTask.getProject());
            if (taskPublisher == null) {
                log.debug("Task '{}' does not have publisher configured", artifactoryTask.getPath());
                return;
            }
            if (StringUtils.isBlank(taskPublisher.getContextUrl())) {
                log.debug("Task '{}' does not have publisher configured with contextUrl attribute", artifactoryTask.getPath());
                return;
            }
            mergeRootAndModuleProps(taskPublisher, propsRoot);
            // Add the task deployed details to the container of all deployed details
            allDeployDetails.put(artifactoryTask.getProject().getName(), getTaskDeployDetails(artifactoryTask));
            if (!taskPublisher.isPublishArtifacts()) {
                log.debug("Task '{}' configured not to deploy artifacts", artifactoryTask.getPath());
                return;
            }
            configureArtifactoryManagerAndDeploy(accRoot, taskPublisher, artifactoryTask.getDeployDetails(), logPrefix);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void mergeRootAndModuleProps(ArtifactoryClientConfiguration.PublisherHandler modulePublisher, Map<String, String> propsRoot) {
        Map<String, String> moduleProps = new HashMap<>(propsRoot);
        moduleProps.putAll(modulePublisher.getProps());
        modulePublisher.getProps().putAll(moduleProps);
    }

    private static void configureArtifactoryManagerAndDeploy(ArtifactoryClientConfiguration accRoot, ArtifactoryClientConfiguration.PublisherHandler publisher, Set<GradleDeployDetails> artifactsDeployDetails, String logPrefix) throws IOException {
        try (ArtifactoryManager artifactoryManager = createArtifactoryManager(publisher)) {
            configureArtifactoryManager(accRoot, artifactoryManager);
            deployArtifacts(
                    artifactsDeployDetails,
                    artifactoryManager,
                    new IncludeExcludePatterns(publisher.getIncludePatterns(), publisher.getExcludePatterns()),
                    logPrefix,
                    publisher.getMinChecksumDeploySizeKb()
            );
        }
    }

    private static ArtifactoryManager createArtifactoryManager(ArtifactoryClientConfiguration.PublisherHandler publisher) {
        String contextUrl = publisher.getContextUrl();
        String username = publisher.getUsername();
        String password = publisher.getPassword();
        if (StringUtils.isBlank(username)) {
            username = "";
        }
        if (StringUtils.isBlank(password)) {
            password = "";
        }
        return new ArtifactoryManager(contextUrl, username, password, new GradleClientLogger(log));
    }

    private static void configureArtifactoryManager(ArtifactoryClientConfiguration accRoot, ArtifactoryManager artifactoryManager) {
        // Configure timeout
        if (accRoot.getTimeout() != null) {
            artifactoryManager.setConnectionTimeout(accRoot.getTimeout());
        }
        // Configure retries
        if (accRoot.getConnectionRetries() != null) {
            artifactoryManager.setConnectionRetries(accRoot.getConnectionRetries());
        }
        log.debug("Deploying artifacts using InsecureTls = " + accRoot.getInsecureTls());
        artifactoryManager.setInsecureTls(accRoot.getInsecureTls());
        // Configure proxy
        configureProxy(accRoot.proxy, artifactoryManager);
    }

    static void configureProxy(ArtifactoryClientConfiguration.ProxyHandler proxy, ArtifactoryManager artifactoryManager) {
        // If no proxy is configured, return
        String proxyHost = proxy.getHost();
        Integer proxyPort = proxy.getPort();
        if (StringUtils.isBlank(proxyHost) || proxyPort == null) {
            return;
        }

        // If the Artifactory URL is in the no proxy list, return
        ProxySelector proxySelector = new ProxySelector(proxyHost, proxyPort, proxy.getUsername(), proxy.getPassword(),
                proxyHost, proxyPort, proxy.getUsername(), proxy.getPassword(), proxy.getNoProxy()
        );
        Proxy proxyHandler = proxySelector.getProxy(artifactoryManager.getUrl());
        if (proxyHandler == null) {
            return;
        }

        // Configure the proxy
        log.debug("Found proxy host '{}' in port '{}'", proxyHost, proxyPort);
        artifactoryManager.setProxyConfiguration(proxyHandler.getHost(), proxyHandler.getPort(), proxyHandler.getUsername(), proxyHandler.getPassword());
    }


    private static void deployArtifacts(Set<GradleDeployDetails> artifactsDeployDetails, ArtifactoryManager artifactoryManager, IncludeExcludePatterns patterns, String logPrefix, int minChecksumDeploySizeKb)
            throws IOException {
        for (GradleDeployDetails detail : artifactsDeployDetails) {
            DeployDetails deployDetails = detail.getDeployDetails();
            String artifactPath = deployDetails.getArtifactPath();
            if (PatternMatcher.pathConflicts(artifactPath, patterns)) {
                log.lifecycle("Skipping the deployment of '{}' due to the defined include-exclude patterns.", artifactPath);
                continue;
            }
            try {
                ArtifactoryUploadResponse response = artifactoryManager.upload(deployDetails, logPrefix, minChecksumDeploySizeKb);
                detail.getDeployDetails().setDeploySucceeded(true);
                detail.getDeployDetails().setSha256(response.getChecksums().getSha256());
                // When a maven SNAPSHOT artifact is deployed, Artifactory adds a timestamp to the artifact name, after the artifact is deployed.
                // ArtifactPath needs to be updated accordingly.
                detail.getDeployDetails().setArtifactPath(response.getPath());
                log.info("{}Deployed artifact to: {}", logPrefix, response.getUri());
            } catch (IOException e) {
                detail.getDeployDetails().setDeploySucceeded(false);
                detail.getDeployDetails().setSha256("");
                throw e;
            }
        }
    }

    /**
     * Convert the GradleDeployDetails set to DeployDetails for a given task
     */
    private static Set<DeployDetails> getTaskDeployDetails(ArtifactoryTask artifactoryTask) {
        Set<DeployDetails> deployDetailsSet = new LinkedHashSet<>();
        for (GradleDeployDetails details : artifactoryTask.getDeployDetails()) {
            deployDetailsSet.add(details.getDeployDetails());
        }
        return deployDetailsSet;
    }

    public static void deployBuildInfo(ArtifactoryClientConfiguration accRoot, BuildInfo buildInfo, Map<String, Set<DeployDetails>> allDeployDetails) throws IOException {
        if (accRoot.publisher.getContextUrl() == null) {
            return;
        }
        try (ArtifactoryManager artifactoryManager = createArtifactoryManager(accRoot.publisher)) {
            configureProxy(accRoot.proxy, artifactoryManager);

            if (accRoot.publisher.isPublishBuildInfo()) {
                log.debug("Publishing build info to artifactory at: '{}'", accRoot.publisher.getContextUrl());
                Utils.sendBuildAndBuildRetention(artifactoryManager, buildInfo, accRoot);
            }

            exportDeployedArtifacts(accRoot, allDeployDetails);
        }
    }

    private static void exportDeployedArtifacts(ArtifactoryClientConfiguration accRoot, Map<String, Set<DeployDetails>> allDeployDetails) {
        String exportArtifactsPath = accRoot.info.getDeployableArtifactsFilePath();
        if (StringUtils.isEmpty(exportArtifactsPath)) {
            return;
        }
        try {
            log.debug("Exporting deployable artifacts to '{}'", exportArtifactsPath);
            DeployableArtifactsUtils.saveDeployableArtifactsToFile(allDeployDetails, new File(exportArtifactsPath), accRoot.info.isBackwardCompatibleDeployableArtifacts());
        } catch (Exception e) {
            log.error("Failed writing deployable artifacts to file: ", e);
            throw new RuntimeException("Failed writing deployable artifacts to file", e);
        }
    }
}
