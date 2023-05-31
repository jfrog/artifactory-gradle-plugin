package org.jfrog.buildinfo.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.publish.ivy.IvyArtifact;
import org.gradle.api.publish.ivy.internal.publication.IvyPublicationInternal;
import org.gradle.api.publish.ivy.internal.publisher.IvyNormalizedPublication;
import org.gradle.api.publish.ivy.internal.publisher.IvyPublicationIdentity;
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.buildinfo.extractor.details.GradleDeployDetails;
import org.jfrog.buildinfo.extractor.details.PublishArtifactInfo;
import org.jfrog.buildinfo.tasks.CollectDeployDetailsTask;

import javax.xml.namespace.QName;
import java.io.File;
import java.util.Map;
import java.util.Set;

import static org.jfrog.build.api.util.FileChecksumCalculator.*;

public class PublicationUtils {

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

    public static void extractMavenDeployDetails(MavenPublicationInternal mavenPublicationInternal, CollectDeployDetailsTask destination) {
//        String publicationName = mavenPublicationInternal.getName();
//        mavenPublicationInternal.asNormalisedPublication().getPomArtifact().getFile();
//        MavenNormalizedPublication mavenNormalizedPublication = mavenPublicationInternal.asNormalisedPublication();
//
//        // First adding the Maven descriptor (if the build is configured to add it):
//        File pomFile = mavenNormalizedPublication.getPomArtifact().getFile();
//        if (isPublishMaven()) {
//            DeployDetails.Builder builder = createArtifactBuilder(pomFile, publicationName);
//            if (builder != null) {
//                PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
//                        mavenPublication.getArtifactId(), "pom", "pom", null, pomFile);
//                addMavenArtifactToDeployDetails(destination, publicationName, builder, artifactInfo, mavenPublication);
//            }
//        }
//
//        boolean legacy = false;
//        Set<MavenArtifact> artifacts = new HashSet<>();
//        try {
//            // Gradle 5.0 and above:
//            artifacts = mavenNormalizedPublication.getAdditionalArtifacts();
//            // Second adding the main artifact of the publication, if present
//            if (mavenNormalizedPublication.getMainArtifact() != null) {
//                createPublishArtifactInfoAndAddToDeployDetails(mavenNormalizedPublication.getMainArtifact(), destination, mavenPublication, publicationName);
//            }
//        } catch (IllegalStateException exception) {
//            // The Jar task is disabled, and therefore getMainArtifact() threw an exception:
//            // "Artifact api.jar wasn't produced by this build."
//            log.warn("Illegal state detected at Maven publication '{}', {}: {}", publicationName, getProject(), exception.getMessage());
//        } catch (NoSuchMethodError error) {
//            // Compatibility with older versions of Gradle:
//            artifacts = mavenNormalizedPublication.getAllArtifacts();
//            legacy = true;
//        }
//
//        // Third adding all additional artifacts - includes Gradle Module Metadata when produced
//        for (MavenArtifact artifact : artifacts) {
//            if (legacy && artifact.getFile().equals(pomFile)) {
//                // Need to skip the POM file for Gradle < 5.0
//                continue;
//            }
//            createPublishArtifactInfoAndAddToDeployDetails(artifact, destination, mavenPublication, publicationName);
//        }
    }

    private static void addArtifactInfoToDeployDetails(CollectDeployDetailsTask destination, String publicationName,
                                                       DeployDetails.Builder builder, PublishArtifactInfo artifactInfo, String artifactPath) {
        Project project = destination.getProject();
        ArtifactoryClientConfiguration.PublisherHandler publisher = ConventionUtils.getPublisherHandler(project);
        if (publisher != null) {
            builder.targetRepository(getTargetRepository(artifactPath, publisher));
//            Map<String, String> propsToAdd = getPropsToAdd(artifactInfo, publicationName);
//            builder.addProperties(propsToAdd);
            destination.deployDetails.add(new GradleDeployDetails(artifactInfo, builder.build(), project));
        }
    }

    /**
     * @param deployPath the full path string to deploy the artifact.
     * @return Target deployment repository.
     * If snapshot repository is defined and artifact's version is snapshot, deploy to snapshot repository.
     * Otherwise, return the corresponding release repository.
     */
    protected static String getTargetRepository(String deployPath, ArtifactoryClientConfiguration.PublisherHandler publisher) {
        String snapshotsRepository = publisher.getSnapshotRepoKey();
        if (snapshotsRepository != null && deployPath.contains("-SNAPSHOT")) {
            return snapshotsRepository;
        }
        if (StringUtils.isNotEmpty(publisher.getReleaseRepoKey())) {
            return publisher.getReleaseRepoKey();
        }
        return publisher.getRepoKey();
    }

    /**
     * Creates a DeployDetails.Builder configured for a given Gradle artifact
     * @param file - the artifact file
     * @param publicationName - the publication name that published this artifact
     * @return DeployDetails.Builder configured for Gradle artifact
     */
    public static DeployDetails.Builder createArtifactBuilder(File file, String publicationName) {
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
