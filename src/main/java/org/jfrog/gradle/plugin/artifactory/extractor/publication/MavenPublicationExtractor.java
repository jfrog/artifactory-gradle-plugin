package org.jfrog.gradle.plugin.artifactory.extractor.publication;

import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.maven.MavenPublication;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.LayoutPatterns;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;

import java.io.File;
import java.util.Map;

import static org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils.addArtifactInfoToDeployDetails;

public class MavenPublicationExtractor extends PublicationExtractor<ArtifactoryTask.MavenPublicationData> {

    public MavenPublicationExtractor(ArtifactoryTask artifactoryTask) {
        super(artifactoryTask);
    }

    @Override
    public void extractDeployDetails(ArtifactoryTask.MavenPublicationData publication) {
        // First adding the Maven descriptor (if the build is configured to add it):
        extractMavenDescriptor(publication);

        // Then extract artifacts
        extractMavenArtifacts(publication);
    }

    @Override
    protected void addArtifactToDeployDetails(ArtifactoryTask.MavenPublicationData publication, DeployDetails.Builder builder, PublishArtifactInfo artifactInfo) {
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
    protected String getPublicationArtifactId(ArtifactoryTask.MavenPublicationData publication) {
        return publication.getArtifactId();
    }

    @Override
    protected String getPublicationName(ArtifactoryTask.MavenPublicationData publication) {
        return publication.getName();
    }

    @Override
    protected boolean isApplicablePublicationType(Class<? extends Publication> publicationType) {
        return MavenPublication.class.isAssignableFrom(publicationType);
    }

    @Override
    protected ArtifactoryTask.MavenPublicationData findPublicationDataByName(String name) {
        for (ArtifactoryTask.MavenPublicationData data : artifactoryTask.getMavenPublicationSnapshots()) {
            if (data.getName().equals(name)) {
                return data;
            }
        }
        return null;
    }

    /**
     * Extract deploy details of the Maven descriptor, if configured to add it and stores them at the given task destination
     */
    private void extractMavenDescriptor(ArtifactoryTask.MavenPublicationData publication) {
        if (!isPublishMaven()) {
            return;
        }
        // Find the pom file from pre-collected data
        File pomFile = findPomFileForPublication(publication);
        if (pomFile == null || !pomFile.exists()) {
            return;
        }
        buildAndPublishArtifactWithSignatures(pomFile, publication, publication.getArtifactId(), "pom", "pom", null, null);
    }

    /**
     * Find the pom file for a given publication using pre-collected MavenPomInfo.
     * The task name for GenerateMavenPom follows the pattern "generatePomFileFor{PublicationName}Publication".
     */
    private File findPomFileForPublication(ArtifactoryTask.MavenPublicationData publication) {
        String expectedTaskName = "generatePomFileFor" + capitalize(publication.getName()) + "Publication";
        for (ArtifactoryTask.MavenPomInfo pomInfo : artifactoryTask.getMavenPomInfos()) {
            if (pomInfo.getPublicationName().equals(expectedTaskName)) {
                return pomInfo.getDestination();
            }
        }
        return null;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Checks if the given task should publish the Maven descriptor
     * Checks global publisher config if exists, if not exists checks specific task configuration
     */
    private boolean isPublishMaven() {
        ArtifactoryClientConfiguration.PublisherHandler publisher = artifactoryTask.getPublisherFromSnapshot();
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
    private void extractMavenArtifacts(ArtifactoryTask.MavenPublicationData publication) {
        for (ArtifactoryTask.MavenArtifactData artifact : publication.getArtifacts()) {
            createPublishArtifactInfoAndAddToDeployDetails(artifact, publication);
        }
    }

    private void createPublishArtifactInfoAndAddToDeployDetails(ArtifactoryTask.MavenArtifactData artifact, ArtifactoryTask.MavenPublicationData publication) {
        File file = artifact.getFile();
        buildAndPublishArtifactWithSignatures(file, publication, publication.getArtifactId(), artifact.getExtension(), artifact.getExtension(), artifact.getClassifier(), null);
    }
}
