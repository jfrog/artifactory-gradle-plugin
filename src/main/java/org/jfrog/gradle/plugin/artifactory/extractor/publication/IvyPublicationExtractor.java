package org.jfrog.gradle.plugin.artifactory.extractor.publication;

import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.ivy.IvyArtifact;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.ivy.tasks.GenerateIvyDescriptor;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;

import javax.xml.namespace.QName;
import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils.addArtifactInfoToDeployDetails;
import static org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils.createArtifactBuilder;

public class IvyPublicationExtractor extends PublicationExtractor<IvyPublication> {

    public IvyPublicationExtractor(ArtifactoryTask artifactoryTask) {
        super(artifactoryTask);
    }

    @Override
    public void extractDeployDetails(IvyPublication publication) {
        // Prepare needed attributes to extract
        Map<QName, String> extraInfo = publication.getDescriptor().getExtraInfo().asMap();

        // First adding the Ivy descriptor (if the build is configured to add it):
        File ivyFile = extractIvyDescriptor(publication, extraInfo);

        // Second adding all artifacts, skipping the ivy file
        extractIvyArtifacts(ivyFile, publication, extraInfo);
    }

    @Override
    protected void addArtifactToDeployDetails(IvyPublication publication, DeployDetails.Builder builder, PublishArtifactInfo artifactInfo) {
        ArtifactoryClientConfiguration.PublisherHandler publisher = ExtensionsUtils.getPublisherHandler(artifactoryTask.getProject());
        if (publisher == null) {
            return;
        }

        String pattern;
        if ("ivy".equals(artifactInfo.getType())) {
            pattern = publisher.getIvyPattern();
        } else {
            pattern = publisher.getIvyArtifactPattern();
        }
        String gid = publication.getOrganisation();
        if (publisher.isM2Compatible()) {
            gid = gid.replace(".", "/");
        }

        // TODO: Gradle should support multi params
        Map<String, String> extraTokens = artifactInfo.getExtraTokens();
        String artifactPath = IvyPatternHelper.substitute(
                pattern, gid, publication.getModule(),
                publication.getRevision(), artifactInfo.getName(), artifactInfo.getType(),
                artifactInfo.getExtension(), publication.getName(),
                extraTokens, null);
        builder.artifactPath(artifactPath);
        addArtifactInfoToDeployDetails(artifactoryTask, publication.getName(), builder, artifactInfo, artifactPath);
    }

    @Override
    protected String getPublicationArtifactId(IvyPublication publication) {
        return publication.getModule();
    }

    @Override
    protected boolean isApplicablePublication(Publication publication) {
        return publication instanceof IvyPublication;
    }

    /**
     * Extract deploy details of the Ivy descriptor, if configured to add it and stores them at the given task destination
     */
    private File extractIvyDescriptor(IvyPublication publication, Map<QName, String> extraInfo) {
        if (!isPublishIvy(artifactoryTask)) {
            return null;
        }
        GenerateIvyDescriptor generateIvyDescriptor = artifactoryTask.getProject().getTasks().withType(GenerateIvyDescriptor.class).stream()
                .filter(generateIvyDescriptorCandidate -> Objects.equals(generateIvyDescriptorCandidate.getDescriptor(), publication.getDescriptor()))
                .findAny()
                .orElse(null);
        if (generateIvyDescriptor == null) {
            return null;
        }
        File ivyFile = generateIvyDescriptor.getDestination();
        if (!ivyFile.exists()) {
            return null;
        }
        DeployDetails.Builder builder = createArtifactBuilder(ivyFile, publication.getName());
        PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                publication.getModule(), "xml", "ivy", null, extraInfo, ivyFile);
        addArtifactToDeployDetails(publication, builder, artifactInfo);
        return ivyFile;
    }

    /**
     * Checks if the given task should publish the Ivy descriptor
     * Checks global publisher config if exists, if not exists checks specific task configuration
     */
    private boolean isPublishIvy(ArtifactoryTask task) {
        ArtifactoryClientConfiguration.PublisherHandler publisher = ExtensionsUtils.getPublisherHandler(task.getProject());
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
    private void extractIvyArtifacts(File ivyFile, IvyPublication publication, Map<QName, String> extraInfo) {
        Set<IvyArtifact> artifacts = publication.getArtifacts();
        for (IvyArtifact artifact : artifacts) {
            File file = artifact.getFile();
            // Skip the ivy file
            if (file.equals(ivyFile)) {
                continue;
            }
            DeployDetails.Builder builder = createArtifactBuilder(file, publication.getName());
            PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                    artifact.getName(), artifact.getExtension(), artifact.getType(), artifact.getClassifier(),
                    extraInfo, file);
            addArtifactToDeployDetails(publication, builder, artifactInfo);
        }
    }
}
