package org.jfrog.gradle.plugin.artifactory.extractor.publication;

import org.gradle.api.publish.Publication;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.jfrog.build.extractor.clientConfiguration.deploy.DeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;

import java.io.File;

import static org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils.createArtifactBuilder;

public abstract class PublicationExtractor<ActualPublication extends Publication> {
    protected ArtifactoryTask artifactoryTask;

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

            DeployDetails.Builder builder = createArtifactBuilder(moduleMetadata, generateModuleMetadata.getPublication().get().getName());

            //noinspection unchecked
            ActualPublication actualPublication = (ActualPublication) publication;
            PublishArtifactInfo artifactInfo = new PublishArtifactInfo(getPublicationArtifactId(actualPublication), "module", "module", null, moduleMetadata);
            addArtifactToDeployDetails(actualPublication, builder, artifactInfo);
        }
    }
}
