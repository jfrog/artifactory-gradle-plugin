package org.jfrog.gradle.plugin.artifactory.extractor.publication;

import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.ivy.IvyPublication;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;

import javax.xml.namespace.QName;
import java.io.File;
import java.util.Map;

import static org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils.addArtifactInfoToDeployDetails;

public class IvyPublicationExtractor extends PublicationExtractor<ArtifactoryTask.IvyPublicationData> {

    public IvyPublicationExtractor(ArtifactoryTask artifactoryTask) {
        super(artifactoryTask);
    }

    @Override
    public void extractDeployDetails(ArtifactoryTask.IvyPublicationData publication) {
        // Prepare needed attributes to extract
        Map<QName, String> extraInfo = publication.getExtraInfo();

        // First adding the Ivy descriptor (if the build is configured to add it):
        File ivyFile = extractIvyDescriptor(publication, extraInfo);

        // Second adding all artifacts, skipping the ivy file
        extractIvyArtifacts(ivyFile, publication, extraInfo);
    }

    @Override
    protected void addArtifactToDeployDetails(ArtifactoryTask.IvyPublicationData publication, DeployDetails.Builder builder, PublishArtifactInfo artifactInfo) {
        ArtifactoryClientConfiguration.PublisherHandler publisher = artifactoryTask.getPublisherFromSnapshot();
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
    protected String getPublicationArtifactId(ArtifactoryTask.IvyPublicationData publication) {
        return publication.getModule();
    }

    @Override
    protected String getPublicationName(ArtifactoryTask.IvyPublicationData publication) {
        return publication.getName();
    }

    @Override
    protected boolean isApplicablePublicationType(Class<? extends Publication> publicationType) {
        return IvyPublication.class.isAssignableFrom(publicationType);
    }

    @Override
    protected ArtifactoryTask.IvyPublicationData findPublicationDataByName(String name) {
        for (ArtifactoryTask.IvyPublicationData data : artifactoryTask.getIvyPublicationSnapshots()) {
            if (data.getName().equals(name)) {
                return data;
            }
        }
        return null;
    }

    /**
     * Extract deploy details of the Ivy descriptor, if configured to add it and stores them at the given task destination
     */
    private File extractIvyDescriptor(ArtifactoryTask.IvyPublicationData publication, Map<QName, String> extraInfo) {
        if (!isPublishIvy(artifactoryTask)) {
            return null;
        }
        // Find the ivy descriptor file from pre-collected data
        File ivyFile = findIvyDescriptorForPublication(publication);
        if (ivyFile == null || !ivyFile.exists()) {
            return null;
        }
        buildAndPublishArtifactWithSignatures(ivyFile, publication, publication.getModule(), "xml", "ivy", null, extraInfo);
        return ivyFile;
    }

    /**
     * Find the ivy descriptor file for a given publication using pre-collected IvyDescriptorInfo.
     * The task name for GenerateIvyDescriptor follows the pattern "generateDescriptorFileFor{PublicationName}Publication".
     */
    private File findIvyDescriptorForPublication(ArtifactoryTask.IvyPublicationData publication) {
        String expectedTaskName = "generateDescriptorFileFor" + capitalize(publication.getName()) + "Publication";
        for (ArtifactoryTask.IvyDescriptorInfo info : artifactoryTask.getIvyDescriptorInfos()) {
            if (info.getPublicationName().equals(expectedTaskName)) {
                return info.getDestination();
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
     * Checks if the given task should publish the Ivy descriptor
     * Checks global publisher config if exists, if not exists checks specific task configuration
     */
    private boolean isPublishIvy(ArtifactoryTask task) {
        ArtifactoryClientConfiguration.PublisherHandler publisher = task.getPublisherFromSnapshot();
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
    private void extractIvyArtifacts(File ivyFile, ArtifactoryTask.IvyPublicationData publication, Map<QName, String> extraInfo) {
        for (ArtifactoryTask.IvyArtifactData artifact : publication.getArtifacts()) {
            File file = artifact.getFile();
            // Skip the ivy file
            if (file.equals(ivyFile)) {
                continue;
            }
            buildAndPublishArtifactWithSignatures(file, publication, artifact.getName(), artifact.getExtension(), artifact.getType(), artifact.getClassifier(), extraInfo);
        }
    }
}
