package org.jfrog.gradle.plugin.artifactory.extractor;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.api.builder.PromotionStatusBuilder;
import org.jfrog.build.api.release.Promotion;
import org.jfrog.build.extractor.BuildInfoExtractor;
import org.jfrog.build.extractor.ModuleExtractorUtils;
import org.jfrog.build.extractor.builder.BuildInfoBuilder;
import org.jfrog.build.extractor.ci.*;
import org.jfrog.build.extractor.ci.Module;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.packageManager.PackageManagerUtils;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static org.jfrog.build.extractor.ci.BuildInfoProperties.BUILD_INFO_ENVIRONMENT_PREFIX;
import static org.jfrog.gradle.plugin.artifactory.Constant.*;

public class GradleBuildInfoExtractor implements BuildInfoExtractor<Project> {

    private static final Logger log = Logging.getLogger(GradleBuildInfoExtractor.class);

    private final ArtifactoryClientConfiguration clientConf;
    private final List<ModuleInfoFileProducer> moduleInfoFileProducers;

    public GradleBuildInfoExtractor(ArtifactoryClientConfiguration clientConf, List<ModuleInfoFileProducer> moduleInfoFileProducers) {
        this.clientConf = clientConf;
        this.moduleInfoFileProducers = moduleInfoFileProducers;
    }

    @Override
    public BuildInfo extract(Project rootProject) {
        BuildInfo buildInfo = createBuildInfoBuilder().build();
        PackageManagerUtils.collectEnvAndFilterProperties(clientConf, buildInfo);
        removeResolutionProperties(buildInfo);
        log.debug("BuildInfo extracted = " + buildInfo);
        return buildInfo;
    }

    /**
     * Creates a builder for this project filled with all the collected information and ready to be built.
     *
     * @return BuildInfoBuilder with fields sets by the client configurations, ready to be used.
     */
    private BuildInfoBuilder createBuildInfoBuilder() {
        BuildInfoBuilder bib = createBaseBuilder();

        // Dependencies & Artifacts
        populateBuilderModulesFields(bib);

        // Run Parameters (Properties)
        for (Map.Entry<String, String> runParam : clientConf.info.getRunParameters().entrySet()) {
            MatrixParameter matrixParameter = new MatrixParameter(runParam.getKey(), runParam.getValue());
            bib.addRunParameters(matrixParameter);
        }

        // Other Meta Data
        populateBuilderAgentFields(bib);
        populateBuilderParentFields(bib);
        populateBuilderArtifactoryPluginVersionField(bib);

        Date buildStartDate = populateBuilderDateTimeFields(bib);
        String principal = populateBuilderPrincipalField(bib);
        String artifactoryPrincipal = populateBuilderArtifactoryPrincipalField(bib);

        // Other services information
        populateBuilderPromotionFields(bib, buildStartDate, principal, artifactoryPrincipal);
        populateBuilderVcsFields(bib);
        populateBuilderIssueTrackerFields(bib);

        return bib;
    }

    /**
     * Create a BuildInfoBuilder with name,number and project base on the client configurations
     */
    private BuildInfoBuilder createBaseBuilder() {
        return new BuildInfoBuilder(clientConf.info.getBuildName()).number(clientConf.info.getBuildNumber()).project(clientConf.info.getProject());
    }

    /**
     * Fill the module field base on the client configurations
     *
     * @param bib - the builder to set its fields
     */
    private void populateBuilderModulesFields(BuildInfoBuilder bib) {
        Set<File> moduleFilesWithModules = moduleInfoFileProducers.stream()
                .filter(ModuleInfoFileProducer::hasModules)
                .flatMap(moduleInfoFileProducer -> moduleInfoFileProducer.getModuleInfoFiles().getFiles().stream())
                .collect(Collectors.toSet());

        moduleFilesWithModules.forEach(moduleFile -> {
            try {
                Module module = ModuleExtractorUtils.readModuleFromFile(moduleFile);
                List<Artifact> artifacts = module.getArtifacts();
                List<Dependency> dependencies = module.getDependencies();
                if ((artifacts != null && !artifacts.isEmpty()) || (dependencies != null && !dependencies.isEmpty())) {
                    bib.addModule(module);
                }
            } catch (IOException e) {
                throw new RuntimeException("Cannot load module info from file: " + moduleFile.getAbsolutePath(), e);
            }
        });
    }

    private void populateBuilderArtifactoryPluginVersionField(BuildInfoBuilder bib) {
        String artifactoryPluginVersion = clientConf.info.getArtifactoryPluginVersion();
        if (StringUtils.isBlank(artifactoryPluginVersion)) {
            artifactoryPluginVersion = "Unknown";
        }
        bib.artifactoryPluginVersion(artifactoryPluginVersion);
    }

    private void populateBuilderParentFields(BuildInfoBuilder bib) {
        String parentName = clientConf.info.getParentBuildName();
        String parentNumber = clientConf.info.getParentBuildNumber();
        if (parentName != null && parentNumber != null) {
            bib.parentName(parentName);
            bib.parentNumber(parentNumber);
        }
    }

    private Date populateBuilderDateTimeFields(BuildInfoBuilder bib) {
        String buildStartedIso = clientConf.info.getBuildStarted();
        Date buildStartDate = null;
        try {
            buildStartDate = new SimpleDateFormat(BuildInfo.STARTED_FORMAT).parse(buildStartedIso);
        } catch (ParseException e) {
            log.error("Build start date format error: " + buildStartedIso, e);
        }
        bib.started(buildStartedIso);

        long durationMillis = buildStartDate != null ? System.currentTimeMillis() - buildStartDate.getTime() : 0;
        bib.durationMillis(durationMillis);

        return buildStartDate;
    }

    /**
     * Fill the agent fields base on the client configurations
     *
     * @param bib - the builder to set its fields
     */
    private void populateBuilderAgentFields(BuildInfoBuilder bib) {
        BuildAgent buildAgent = new BuildAgent(clientConf.info.getBuildAgentName(), clientConf.info.getBuildAgentVersion());
        bib.buildAgent(buildAgent);

        // CI agent
        String agentName = clientConf.info.getAgentName();
        String agentVersion = clientConf.info.getAgentVersion();
        if (StringUtils.isNoneBlank(agentName, agentVersion)) {
            bib.agent(new Agent(agentName, agentVersion));
        } else {
            // Fallback for standalone builds
            bib.agent(new Agent(buildAgent.getName(), buildAgent.getVersion()));
        }

        // The CI url that initiated the build
        String buildUrl = clientConf.info.getBuildUrl();
        if (StringUtils.isNotBlank(buildUrl)) {
            bib.url(buildUrl);
        }
    }

    /**
     * Fill the Version Control related fields base on the client configurations
     *
     * @param bib - the builder to set its fields
     */
    private void populateBuilderVcsFields(BuildInfoBuilder bib) {
        Vcs vcs = new Vcs(clientConf.info.getVcsUrl(), clientConf.info.getVcsRevision(), clientConf.info.getVcsBranch(), clientConf.info.getVcsMessage());
        if (!vcs.isEmpty()) {
            bib.vcs(Collections.singletonList(vcs));
        }
    }

    /**
     * Fill the Issue-Tracker (Jira) related fields base on the client configurations
     *
     * @param bib - the builder to set its fields
     */
    private void populateBuilderIssueTrackerFields(BuildInfoBuilder bib) {
        String issueTrackerName = clientConf.info.issues.getIssueTrackerName();
        if (StringUtils.isNotBlank(issueTrackerName)) {
            Issues issues = new Issues();
            issues.setAggregateBuildIssues(clientConf.info.issues.getAggregateBuildIssues());
            issues.setAggregationBuildStatus(clientConf.info.issues.getAggregationBuildStatus());
            issues.setTracker(new IssueTracker(issueTrackerName, clientConf.info.issues.getIssueTrackerVersion()));
            Set<Issue> affectedIssuesSet = clientConf.info.issues.getAffectedIssuesSet();
            if (!affectedIssuesSet.isEmpty()) {
                issues.setAffectedIssues(affectedIssuesSet);
            }
            bib.issues(issues);
        }
    }

    /**
     * Fill the principal (ciUsername) field base on the client configurations
     *
     * @param bib - the builder to set its fields
     */
    private String populateBuilderPrincipalField(BuildInfoBuilder bib) {
        String principal = overrideUserNameValueIfExists(clientConf.info.getPrincipal());
        bib.principal(principal);
        return principal;
    }

    /**
     * Fill the artifactoryPrincipal (username) field base on the client configurations
     *
     * @param bib - the builder to set its fields
     */
    private String populateBuilderArtifactoryPrincipalField(BuildInfoBuilder bib) {
        String artifactoryPrincipal = overrideUserNameValueIfExists(clientConf.publisher.getUsername());
        bib.artifactoryPrincipal(artifactoryPrincipal);
        return artifactoryPrincipal;
    }

    private String overrideUserNameValueIfExists(String val) {
        return StringUtils.isBlank(val) ? System.getProperty("user.name") : val;
    }

    /**
     * Fill the Promotion fields base on the given client configurations
     *
     * @param bib - the builder to set its fields
     */
    private void populateBuilderPromotionFields(BuildInfoBuilder bib, Date buildStartDate, String principal, String artifactoryPrincipal) {
        if (clientConf.info.isReleaseEnabled()) {
            String stagingRepository = clientConf.publisher.getRepoKey();
            String comment = clientConf.info.getReleaseComment();
            if (comment == null) {
                comment = "";
            }
            bib.addStatus(new PromotionStatusBuilder(Promotion.STAGED).timestampDate(buildStartDate)
                    .comment(comment).repository(stagingRepository)
                    .ciUser(principal).user(artifactoryPrincipal).build());
        }
    }

    /**
     * Remove the injected resolution repository environment variables from the build-info.
     *
     * @param buildInfo - The build info
     */
    private void removeResolutionProperties(BuildInfo buildInfo) {
        Properties properties = buildInfo.getProperties();
        if (properties == null) {
            return;
        }
        properties.remove(BUILD_INFO_ENVIRONMENT_PREFIX + RESOLUTION_URL_ENV);
        properties.remove(BUILD_INFO_ENVIRONMENT_PREFIX + RESOLUTION_USERNAME_ENV);
        properties.remove(BUILD_INFO_ENVIRONMENT_PREFIX + RESOLUTION_PASSWORD_ENV);
    }
}
