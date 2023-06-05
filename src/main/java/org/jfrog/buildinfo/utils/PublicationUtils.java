package org.jfrog.buildinfo.utils;

import com.google.common.collect.Multimap;
import org.apache.commons.lang3.StringUtils;
import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.publish.ivy.IvyArtifact;
import org.gradle.api.publish.ivy.internal.publication.IvyPublicationInternal;
import org.gradle.api.publish.ivy.internal.publisher.IvyNormalizedPublication;
import org.gradle.api.publish.ivy.internal.publisher.IvyPublicationIdentity;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal;
import org.gradle.api.publish.maven.internal.publisher.MavenNormalizedPublication;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.clientConfiguration.ArtifactSpec;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.LayoutPatterns;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.buildinfo.extractor.details.GradleDeployDetails;
import org.jfrog.buildinfo.extractor.details.PublishArtifactInfo;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;

import javax.xml.namespace.QName;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.jfrog.build.api.util.FileChecksumCalculator.*;

public class PublicationUtils {

    private static final Logger log = Logging.getLogger(PublicationUtils.class);

    /**
     * Extract Ivy publication artifacts, creates deploy details for it and stores them at the given destination
     * @param ivyPublicationInternal - ivy publication to extract details from
     * @param destination - task to collect and store the created details
     */
    public static void extractIvyDeployDetails(IvyPublicationInternal ivyPublicationInternal, CollectDeployDetailsTask destination) {
        // Prepare needed attributes to extract
        String publicationName = ivyPublicationInternal.getName();
        IvyNormalizedPublication ivyNormalizedPublication = ivyPublicationInternal.asNormalisedPublication();
        IvyPublicationIdentity projectIdentity = ivyNormalizedPublication.getProjectIdentity();
        Map<QName, String> extraInfo = ivyPublicationInternal.getDescriptor().getExtraInfo().asMap();

        // First adding the Ivy descriptor (if the build is configured to add it):
        extractIvyDescriptor(destination, publicationName, ivyNormalizedPublication, projectIdentity, extraInfo);

        // Second adding all artifacts, skipping the ivy file
        extractIvyArtifacts(destination, publicationName, ivyNormalizedPublication, projectIdentity, extraInfo);
    }

    /**
     * Extract deploy details of the Ivy descriptor, if configured to add it and stores them at the given task destination
     */
    private static void extractIvyDescriptor(CollectDeployDetailsTask destination, String publicationName, IvyNormalizedPublication ivyNormalizedPublication, IvyPublicationIdentity projectIdentity, Map<QName, String> extraInfo) {
        File ivyFile = ivyNormalizedPublication.getIvyDescriptorFile();
        if (isPublishIvy(destination)) {
            DeployDetails.Builder builder = createArtifactBuilder(ivyFile, publicationName);
            if (builder != null) {
                PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                        projectIdentity.getModule(), "xml", "ivy", null, extraInfo, ivyFile);
                addIvyArtifactToDeployDetails(destination, publicationName, projectIdentity, builder, artifactInfo);
            }
        }
    }

    /**
     * Checks if the given task should publish the Ivy descriptor
     * Checks global publisher config if exists, if not exists checks specific task configuration
     */
    private static boolean isPublishIvy(CollectDeployDetailsTask task) {
        ArtifactoryClientConfiguration.PublisherHandler publisher = ConventionUtils.getPublisherHandler(task.getProject());
        if (publisher == null) {
            return false;
        }
        // Get the value from the client publisher configuration (in case a CI plugin configuration is used):
        Boolean publishIvy = publisher.isIvy();
        // It the value is null, it means that there's no CI Server Artifactory plugin configuration,
        // so the value should be taken from the artifactory DSL inside the gradle script:
        if (publishIvy == null) {
            publishIvy = task.getPublishIvy();
        }
        return publishIvy != null ? publishIvy : true;
    }

    /**
     * Extract deploy details of the Ivy artifacts and stores them at the given task destination
     */
    private static void extractIvyArtifacts(CollectDeployDetailsTask destination, String publicationName, IvyNormalizedPublication ivyNormalizedPublication, IvyPublicationIdentity projectIdentity, Map<QName, String> extraInfo) {
        File ivyFile = ivyNormalizedPublication.getIvyDescriptorFile();
        Set<IvyArtifact> artifacts = ivyNormalizedPublication.getAllArtifacts();
        for (IvyArtifact artifact : artifacts) {
            File file = artifact.getFile();
            // Skip the ivy file
            if (file.equals(ivyFile)) {
                continue;
            }
            DeployDetails.Builder builder = createArtifactBuilder(file, publicationName);
            if (builder == null) {
                continue;
            }
            PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                    artifact.getName(), artifact.getExtension(), artifact.getType(), artifact.getClassifier(),
                    extraInfo, file);
            addIvyArtifactToDeployDetails(destination, publicationName, projectIdentity, builder, artifactInfo);
        }
    }

    /**
     * Adds a given Ivy artifact to deploy details in the given task destination
     */
    private static void addIvyArtifactToDeployDetails(CollectDeployDetailsTask destination, String publicationName,
                                               IvyPublicationIdentity projectIdentity, DeployDetails.Builder builder,
                                               PublishArtifactInfo artifactInfo) {
        ArtifactoryClientConfiguration.PublisherHandler publisher = ConventionUtils.getPublisherHandler(destination.getProject());
        if (publisher == null) {
            return;
        }

        String pattern;
        if ("ivy".equals(artifactInfo.getType())) {
            pattern = publisher.getIvyPattern();
        } else {
            pattern = publisher.getIvyArtifactPattern();
        }
        String gid = projectIdentity.getOrganisation();
        if (publisher.isM2Compatible()) {
            gid = gid.replace(".", "/");
        }

        // TODO: Gradle should support multi params
        Map<String, String> extraTokens = artifactInfo.getExtraTokens();
        String artifactPath = IvyPatternHelper.substitute(
                pattern, gid, projectIdentity.getModule(),
                projectIdentity.getRevision(), artifactInfo.getName(), artifactInfo.getType(),
                artifactInfo.getExtension(), publicationName,
                extraTokens, null);
        builder.artifactPath(artifactPath);
        addArtifactInfoToDeployDetails(destination, publicationName, builder, artifactInfo, artifactPath);
    }

    /**
     * Extract Maven publication artifacts, creates deploy details for it and stores them at the given destination
     * @param mavenPublicationInternal - maven publication to extract details from
     * @param destination - task to collect and store the created details
     */
    public static void extractMavenDeployDetails(MavenPublicationInternal mavenPublicationInternal, CollectDeployDetailsTask destination) {
        String publicationName = mavenPublicationInternal.getName();
        mavenPublicationInternal.asNormalisedPublication().getPomArtifact().getFile();
        MavenNormalizedPublication mavenNormalizedPublication = mavenPublicationInternal.asNormalisedPublication();

        // First adding the Maven descriptor (if the build is configured to add it):
        extractMavenDescriptor(destination, publicationName, mavenPublicationInternal, mavenNormalizedPublication);

        // Then extract artifacts
        extractMavenArtifacts(destination, publicationName, mavenPublicationInternal, mavenNormalizedPublication);
    }

    /**
     * Extract deploy details of the Maven descriptor, if configured to add it and stores them at the given task destination
     */
    private static void extractMavenDescriptor(CollectDeployDetailsTask destination, String publicationName, MavenPublicationInternal mavenPublicationInternal, MavenNormalizedPublication mavenNormalizedPublication) {
        File pomFile = mavenNormalizedPublication.getPomArtifact().getFile();
        if (isPublishMaven(destination)) {
            DeployDetails.Builder builder = createArtifactBuilder(pomFile, publicationName);
            if (builder != null) {
                PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                        mavenPublicationInternal.getArtifactId(), "pom", "pom", null, pomFile);
                addMavenArtifactToDeployDetails(destination, publicationName, builder, artifactInfo, mavenPublicationInternal);
            }
        }
    }

    /**
     * Checks if the given task should publish the Maven descriptor
     * Checks global publisher config if exists, if not exists checks specific task configuration
     */
    private static boolean isPublishMaven(CollectDeployDetailsTask task) {
        ArtifactoryClientConfiguration.PublisherHandler publisher = ConventionUtils.getPublisherHandler(task.getProject());
        if (publisher == null) {
            return false;
        }
        // Get the value from the client publisher configuration (in case a CI plugin configuration is used):
        Boolean publishPom = publisher.isMaven();
        // It the value is null, it means that there's no CI plugin configuration, so the value should be taken from the
        // artifactory DSL inside the gradle script:
        if (publishPom == null) {
            publishPom = task.getPublishPom();
        }
        return publishPom != null ? publishPom : true;
    }

    /**
     * Extract deploy details of the Maven artifacts and stores them at the given task destination
     */
    private static void extractMavenArtifacts(CollectDeployDetailsTask destination, String publicationName, MavenPublicationInternal mavenPublicationInternal, MavenNormalizedPublication mavenNormalizedPublication) {
        Set<MavenArtifact> artifacts = new HashSet<>();
        try {
            artifacts = mavenNormalizedPublication.getAdditionalArtifacts();
            // First adding the main artifact of the publication, if present
            if (mavenNormalizedPublication.getMainArtifact() != null) {
                createPublishArtifactInfoAndAddToDeployDetails(mavenNormalizedPublication.getMainArtifact(), destination, mavenPublicationInternal, publicationName);
            }
        } catch (IllegalStateException exception) {
            // The Jar task is disabled, and therefore getMainArtifact() threw an exception:
            // "Artifact api.jar wasn't produced by this build."
            log.warn("Illegal state detected at Maven publication '{}', {}: {}", publicationName, destination.getProject(), exception.getMessage());
        }

        // Second adding all additional artifacts - includes Gradle Module Metadata when produced
        for (MavenArtifact artifact : artifacts) {
            createPublishArtifactInfoAndAddToDeployDetails(artifact, destination, mavenPublicationInternal, publicationName);
        }
    }

    private static void createPublishArtifactInfoAndAddToDeployDetails(MavenArtifact artifact, CollectDeployDetailsTask destination, MavenPublication mavenPublication, String publicationName) {
        File file = artifact.getFile();
        DeployDetails.Builder builder = createArtifactBuilder(file, publicationName);
        if (builder == null) {
            return;
        }
        PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                mavenPublication.getArtifactId(), artifact.getExtension(),
                artifact.getExtension(), artifact.getClassifier(),
                file);
        addMavenArtifactToDeployDetails(destination, publicationName, builder, artifactInfo, mavenPublication);
    }

    /**
     * Adds a given Maven artifact to deploy details in the given task destination
     */
    private static void addMavenArtifactToDeployDetails(CollectDeployDetailsTask destination, String publicationName, DeployDetails.Builder builder, PublishArtifactInfo artifactInfo, MavenPublication mavenPublication) {
        Map<String, String> extraTokens = artifactInfo.getExtraTokens();
        String artifactPath = IvyPatternHelper.substitute(
                LayoutPatterns.M2_PATTERN, mavenPublication.getGroupId().replace(".", "/"),
                mavenPublication.getArtifactId(),
                mavenPublication.getVersion(),
                artifactInfo.getName(), artifactInfo.getType(),
                artifactInfo.getExtension(), publicationName,
                extraTokens, null);
        builder.artifactPath(artifactPath);
        addArtifactInfoToDeployDetails(destination, publicationName, builder, artifactInfo, artifactPath);
    }

    /**
     * Adds a general artifact to deploy details in the given task destination
     */
    private static void addArtifactInfoToDeployDetails(CollectDeployDetailsTask destination, String publicationName,
                                                       DeployDetails.Builder builder, PublishArtifactInfo artifactInfo, String artifactPath) {
        Project project = destination.getProject();
        ArtifactoryClientConfiguration.PublisherHandler publisher = ConventionUtils.getPublisherHandler(project);
        if (publisher != null) {
            builder.targetRepository(getTargetRepository(artifactPath, publisher));
            Map<String, String> propsToAdd = getPropsToAdd(destination, artifactInfo, publicationName);
            builder.addProperties(propsToAdd);
            destination.deployDetails.add(new GradleDeployDetails(artifactInfo, builder.build(), project));
        }
    }

    /**
     * @param deployPath the full path string to deploy the artifact.
     * @return Target deployment repository.
     * If snapshot repository is defined and artifact's version is snapshot, deploy to snapshot repository.
     * Otherwise, return the corresponding release repository.
     */
    private static String getTargetRepository(String deployPath, ArtifactoryClientConfiguration.PublisherHandler publisher) {
        String snapshotsRepository = publisher.getSnapshotRepoKey();
        if (snapshotsRepository != null && deployPath.contains("-SNAPSHOT")) {
            return snapshotsRepository;
        }
        if (StringUtils.isNotEmpty(publisher.getReleaseRepoKey())) {
            return publisher.getReleaseRepoKey();
        }
        return publisher.getRepoKey();
    }

    private static Map<String, String> getPropsToAdd(CollectDeployDetailsTask destination, PublishArtifactInfo artifact, String publicationName) {
        Project project = destination.getProject();
        Map<String, String> propsToAdd = new HashMap<>(destination.getDefaultProps());
        // Apply artifact-specific props from the artifact specs
        ArtifactSpec spec =
                ArtifactSpec.builder().configuration(publicationName)
                        .group(project.getGroup().toString())
                        .name(project.getName()).version(project.getVersion().toString())
                        .classifier(artifact.getClassifier())
                        .type(artifact.getType()).build();
        Multimap<String, CharSequence> artifactSpecsProperties = destination.artifactSpecs.getProperties(spec);
        addProps(propsToAdd, artifactSpecsProperties);
        return propsToAdd;
    }

    public static void addProps(Map<String, String> target, Multimap<String, CharSequence> props) {
        for (Map.Entry<String, CharSequence> entry : props.entries()) {
            // Make sure all GString are now Java Strings
            String key = entry.getKey();
            String value = entry.getValue().toString();
            //Accumulate multi-value props
            if (!target.containsKey(key)) {
                target.put(key, value);
            } else {
                value = target.get(key) + ", " + value;
                target.put(key, value);
            }
        }
    }

    /**
     * Creates a DeployDetails.Builder configured for a given Gradle artifact
     * @param file - the artifact file
     * @param publicationName - the publication name that published this artifact
     * @return DeployDetails.Builder configured for Gradle artifact
     */
    private static DeployDetails.Builder createArtifactBuilder(File file, String publicationName) {
        if (!file.exists()) {
            throw new GradleException("File '" + file.getAbsolutePath() + "'" +
                    " does not exist, and need to be published from publication " + publicationName);
        }

        DeployDetails.Builder artifactBuilder = new DeployDetails.Builder()
                .file(file)
                .packageType(DeployDetails.PackageType.GRADLE);
        try {
            Map<String, String> checksums =
                    FileChecksumCalculator.calculateChecksums(file, MD5_ALGORITHM, SHA1_ALGORITHM, SHA256_ALGORITHM);
            artifactBuilder.md5(checksums.get(MD5_ALGORITHM)).sha1(checksums.get(SHA1_ALGORITHM)).sha256(checksums.get(SHA256_ALGORITHM));
        } catch (Exception e) {
            throw new GradleException(
                    "Failed to calculate checksums for artifact: " + file.getAbsolutePath(), e);
        }
        return artifactBuilder;
    }
}
