package org.jfrog.gradle.plugin.artifactory.extractor.publication;

import org.gradle.api.publish.Publication;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.plugins.signing.Sign;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;

import javax.xml.namespace.QName;
import java.io.File;
import java.util.Map;

import static org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils.createArtifactBuilder;

public abstract class PublicationExtractor<ActualPublication extends Publication> {
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
     * Return true if the input publication if of the type of subclass publication type.
     *
     * @param publication - The publication to check
     * @return true if the input publication if of the type of subclass publication type
     */
    protected abstract boolean isApplicablePublication(Publication publication);

    /**
     * Extract *.module files publications.
     */
    public void extractModuleInfo() {
        for (GenerateModuleMetadata generateModuleMetadata : artifactoryTask.getProject().getTasks().withType(GenerateModuleMetadata.class)) {
            Publication publication = generateModuleMetadata.getPublication().get();
            if (!isApplicablePublication(publication)) {
                continue;
            }

            File moduleMetadata = generateModuleMetadata.getOutputFile().getAsFile().get();
            if (!moduleMetadata.exists()) {
                continue;
            }

            //noinspection unchecked
            ActualPublication actualPublication = (ActualPublication) publication;
            buildAndPublishArtifactWithSignatures(moduleMetadata, actualPublication, getPublicationArtifactId(actualPublication), "module", "module", null, null);
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
        DeployDetails.Builder builder = createArtifactBuilder(file, publication.getName());
        PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                artifactId, artifactExtension, artifactType, artifactClassifier, extraInfo, file);
        addArtifactToDeployDetails(publication, builder, artifactInfo);
    }

    /**
     * @return true if the Signing plugin was used in the project
     */
    private boolean isSignTaskExists() {
        return !artifactoryTask.getProject().getTasks().withType(Sign.class).isEmpty();
    }
}
