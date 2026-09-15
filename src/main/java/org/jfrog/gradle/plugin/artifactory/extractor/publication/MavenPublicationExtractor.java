package org.jfrog.gradle.plugin.artifactory.extractor.publication;

import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.GenerateMavenPom;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.LayoutPatterns;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;

import java.io.File;
import java.util.Map;
import java.util.Objects;

import static org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils.addArtifactInfoToDeployDetails;

public class MavenPublicationExtractor extends PublicationExtractor<MavenPublication> {

    public MavenPublicationExtractor(ArtifactoryTask artifactoryTask) {
        super(artifactoryTask);
    }

    @Override
    public void extractDeployDetails(MavenPublication publication) {
        // First adding the Maven descriptor (if the build is configured to add it):
        extractMavenDescriptor(publication);

        // Then extract artifacts
        extractMavenArtifacts(publication);
    }

    @Override
    protected void addArtifactToDeployDetails(MavenPublication publication, DeployDetails.Builder builder, PublishArtifactInfo artifactInfo) {
        Map<String, String> extraTokens = artifactInfo.getExtraTokens();
        String artifactPath = IvyPatternHelper.substitute(
                LayoutPatterns.M2_PATTERN, publication.getGroupId().replace(".", "/"),
                publication.getArtifactId(),
                publication.getVersion(),
                artifactInfo.getName(), artifactInfo.getType(),
                artifactInfo.getExtension(), publication.getName(),
                extraTokens, null);
        builder.artifactPath(artifactPath);
        addArtifactInfoToDeployDetails(artifactoryTask, publication.getName(), builder, artifactInfo, artifactPath);
    }

    @Override
    protected String getPublicationArtifactId(MavenPublication publication) {
        return publication.getArtifactId();
    }

    @Override
    protected boolean isApplicablePublication(Publication publication) {
        return publication instanceof MavenPublication;
    }

    /**
     * Extract deploy details of the Maven descriptor, if configured to add it and stores them at the given task destination
     */
    private void extractMavenDescriptor(MavenPublication publication) {
        if (!isPublishMaven()) {
            return;
        }
        GenerateMavenPom generateMavenPom = artifactoryTask.getProject().getTasks().withType(GenerateMavenPom.class).stream()
                .filter(generateMavenPomCandidate -> Objects.equals(generateMavenPomCandidate.getPom(), publication.getPom()))
                .findAny()
                .orElse(null);
        if (generateMavenPom == null) {
            return;
        }
        File pomFile = generateMavenPom.getDestination();
        if (!pomFile.exists()) {
            return;
        }
        buildAndPublishArtifactWithSignatures(pomFile, publication, publication.getArtifactId(), "pom", "pom", null, null);
    }

    /**
     * Checks if the given task should publish the Maven descriptor
     * Checks global publisher config if exists, if not exists checks specific task configuration
     */
    private boolean isPublishMaven() {
        ArtifactoryClientConfiguration.PublisherHandler publisher = ExtensionsUtils.getPublisherHandler(artifactoryTask.getProject());
        if (publisher == null) {
            return false;
        }
        // Get the value from the client publisher configuration (in case a CI plugin configuration is used):
        Boolean publishPom = publisher.isMaven();
        // It the value is null, it means that there's no CI plugin configuration, so the value should be taken from the
        // artifactory DSL inside the gradle script:
        if (publishPom == null) {
            publishPom = artifactoryTask.getPublishPom();
        }
        return publishPom != null ? publishPom : true;
    }

    /**
     * Extract deploy details of the Maven artifacts and stores them at the given task destination
     */
    private void extractMavenArtifacts(MavenPublication publication) {
        for (MavenArtifact artifact : publication.getArtifacts()) {
            createPublishArtifactInfoAndAddToDeployDetails(artifact, publication);
        }
    }

    private void createPublishArtifactInfoAndAddToDeployDetails(MavenArtifact artifact, MavenPublication publication) {
        File file = artifact.getFile();
        buildAndPublishArtifactWithSignatures(file, publication, publication.getArtifactId(), artifact.getExtension(), artifact.getExtension(), artifact.getClassifier(), null);
    }
}
