package org.jfrog.gradle.plugin.artifactory.extractor.publication;

import org.gradle.api.publish.Publication;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;

import javax.xml.namespace.QName;
import java.io.File;
import java.util.Map;

import static org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils.createArtifactBuilder;

public abstract class PublicationExtractor<ActualPublication> {
    protected ArtifactoryTask artifactoryTask;
    // Signature types supported by the Signing plugin.
    private final String[] SIGNATURE_EXTENSIONS = {"asc", "sig"};

    public PublicationExtractor(ArtifactoryTask artifactoryTask) {
        this.artifactoryTask = artifactoryTask;
    }

    /**
     * Extract publication artifacts, creates deploy details for it and stores them at the given destination
     *
     * @param publication - publication to extract details from
     */
    public abstract void extractDeployDetails(ActualPublication publication);

    /**
     * Adds a given artifact to deploy details in the given task destination.
     *
     * @param publication  - Maven or Ivy publication
     * @param builder      - Deploy details builder
     * @param artifactInfo - The artifact info
     */
    protected abstract void addArtifactToDeployDetails(ActualPublication publication, DeployDetails.Builder builder,
                                                       PublishArtifactInfo artifactInfo);

    /**
     * Return the artifact ID of the publication.
     *
     * @param publication - Maven or Ivy publication
     * @return the artifact ID of the publication
     */
    protected abstract String getPublicationArtifactId(ActualPublication publication);

    /**
     * Return the publication name.
     */
    protected abstract String getPublicationName(ActualPublication publication);

    /**
     * Return true if the input publication type is of the type of subclass publication type.
     *
     * @param publicationType - The publication type to check
     * @return true if the input publication type is of the type of subclass publication type
     */
    protected abstract boolean isApplicablePublicationType(Class<? extends Publication> publicationType);

    /**
     * Find a publication by name.
     */
    protected abstract ActualPublication findPublicationDataByName(String name);

    /**
     * Extract *.module files publications.
     */
    public void extractModuleInfo() {
        for (ArtifactoryTask.ModuleMetadataInfo info : artifactoryTask.getModuleMetadataInfos()) {
            if (!isApplicablePublicationType(info.getPublicationType())) {
                continue;
            }

            File moduleMetadata = info.getOutputFile();
            if (!moduleMetadata.exists()) {
                continue;
            }

            // Find the matching publication data by name
            ActualPublication matchingData = findPublicationDataByName(info.getPublicationName());
            if (matchingData == null) {
                continue;
            }

            buildAndPublishArtifactWithSignatures(moduleMetadata, matchingData, getPublicationArtifactId(matchingData), "module", "module", null, null);
        }
    }

    /**
     * Build and publish the artifact and add it to deploy details.
     * If the Signing plugin was used, do the same to the artifact's signatures.
     *
     * @param file               - The file to publish
     * @param publication        - The publication to extract details from
     * @param artifactId         - The artifact ID
     * @param artifactExtension  - The artifact extension
     * @param artifactType       - The artifact type
     * @param artifactClassifier - The artifact classifier
     * @param extraInfo          - Extra information to add to the deploy details
     */
    protected void buildAndPublishArtifactWithSignatures(File file, ActualPublication publication, String artifactId, String artifactExtension, String artifactType, String artifactClassifier, Map<QName, String> extraInfo) {
        buildAndPublishArtifact(file, publication, artifactId, artifactExtension, artifactType, artifactClassifier, extraInfo);
        if (!isSignTaskExists()) {
            return;
        }
        for (String signatureExtension : SIGNATURE_EXTENSIONS) {
            File signatureFile = new File(file.getAbsolutePath() + "." + signatureExtension);
            if (!signatureFile.exists()) {
                continue;
            }
            buildAndPublishArtifact(signatureFile, publication, artifactId, artifactType + "." + signatureExtension, artifactType + "." + signatureExtension, artifactClassifier, extraInfo);
        }
    }

    /**
     * Build and publish the artifact and add it to deploy details.
     *
     * @param file               - The file to publish
     * @param publication        - The publication to extract details from
     * @param artifactId         - The artifact ID
     * @param artifactExtension  - The artifact extension
     * @param artifactType       - The artifact type
     * @param artifactClassifier - The artifact classifier
     * @param extraInfo          - Extra information to add to the deploy details
     */
    private void buildAndPublishArtifact(File file, ActualPublication publication, String artifactId, String artifactExtension, String artifactType, String artifactClassifier, Map<QName, String> extraInfo) {
        DeployDetails.Builder builder = createArtifactBuilder(file, getPublicationName(publication));
        PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                artifactId, artifactExtension, artifactType, artifactClassifier, extraInfo, file);
        addArtifactToDeployDetails(publication, builder, artifactInfo);
    }

    /**
     * @return true if the Signing plugin was used in the project
     */
    private boolean isSignTaskExists() {
        return artifactoryTask.isHasSignTasks();
    }
}
