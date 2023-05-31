package org.jfrog.buildinfo.tasks;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublicationContainer;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.ivy.internal.publication.IvyPublicationInternal;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.buildinfo.Constant;
import org.jfrog.buildinfo.config.ArtifactoryPluginConvention;
import org.jfrog.buildinfo.config.PublisherConfig;
import org.jfrog.buildinfo.extractor.details.GradleDeployDetails;
import org.jfrog.buildinfo.utils.ConventionUtils;
import org.jfrog.buildinfo.utils.PublicationUtils;

import javax.annotation.Nullable;
import java.util.*;

public class CollectDeployDetailsTask extends DefaultTask {
    private static final Logger log = Logging.getLogger(CollectDeployDetailsTask.class);

    // Publication containers
    public Set<IvyPublication> ivyPublications = new HashSet<>();
    public Set<MavenPublication> mavenPublications = new HashSet<>();
    private final Set<Object> publications = new HashSet<>();

    // This project has specified publications to the task
    private boolean publishPublicationsSpecified = false;
    // This project task has been evaluated
    private boolean evaluated = false;
    // Optional flags and attributes with default values
    private final Map<String, Boolean> flags = new HashMap<>();
    @Input
    private boolean skip = false;

    // Container to hold all the details that were collected
    public final Set<GradleDeployDetails> deployDetails = new TreeSet<>();

    /**
     * Make sure this task Depends on Information Collection from all the subprojects.
     * If defaults task is configured for the project
     */
    public void evaluateTask() {
        log.info("<ASSAF> Evaluating {}", getPath());
        evaluated = true;
        Project project = getProject();
        if (isSkip()) {
            log.info("{} task '{}' skipped for project '{}'.",
                    Constant.COLLECT_PUBLISH_INFO_TASK_NAME, this.getPath(), project.getName());
            return;
        }
        // Depends on Information Collection tasks from all the subprojects
        for (Project sub : project.getSubprojects()) {
            Task subCollectInfoTask = sub.getTasks().findByName(Constant.COLLECT_PUBLISH_INFO_TASK_NAME);
            if (subCollectInfoTask != null) {
                dependsOn(subCollectInfoTask);
            }
        }

        ArtifactoryPluginConvention convention = ConventionUtils.getConventionWithPublisher(project);
        if (convention == null) {
            log.info("<ASSAF> No convention configured for {}", getPath());
            return;
        }
        log.info("<ASSAF> Found convention with publisher configured for {}", getPath());
        // Configure the task using the "defaults" action (delegate to the task)
        PublisherConfig config = convention.getPublisherConfig();
        Action<CollectDeployDetailsTask> defaultsAction = config.getDefaultsAction();
        if (defaultsAction != null) {
            log.info("<ASSAF> Delegating {} to defaults", getPath());
            defaultsAction.execute(this);
        }
    }

    /**
     * Task input attribute configuration to specify what publications will be included in the publication to Artifactory
     * @param publications - List of entries that can be:
     *                    * Any Publication object
     *                    * String (ID of known Publication)
     *                    * 'ALL_PUBLICATIONS' a special constant that will try to include all publications if exist
     */
    public void publications(Object... publications) {
        if (publications != null){
            this.publications.addAll(Arrays.asList(publications));
            checkDependsOnArtifactsToPublish();
        }
    }

    /**
     * Extract the publications arguments for the task and make sure this task dependsOn the specified publications tasks.
     */
    private void checkDependsOnArtifactsToPublish() {
        extractPublications();
        if (!hasPublications()) {
            if (publishPublicationsSpecified) {
                // some publication were declared but not found
                log.warn("None of the specified publications matched for project '{}' - nothing to publish.",
                        getProject().getPath());
            } else {
                log.debug("No publications specified for project '{}'", getProject().getPath());
            }
            return;
        }
        createDependencyOnIvyPublications();
        createDependencyOnMavenPublications();
    }

    /**
     * Extract the given publications arguments, process each entry, validate the argument and fetch its matching Publication
     */
    private void extractPublications() {
        if (this.publications.isEmpty()) {
            return;
        }
        for (Object publication : publications) {
            if (publication instanceof CharSequence) {
                // Specified by String
                addPublication((CharSequence) publication);
            } else if (publication instanceof Publication) {
                // Specified by Publication object
                addPublication((Publication) publication);
            } else {
                log.error("Publication type '{}' not supported in task '{}'", publication,getClass().getName());
            }
        }
        publishPublicationsSpecified = true;
    }

    /**
     * Add a Publication to the task by String ID.
     * @param publication - the ID of the publication or 'ALL_PUBLICATIONS' to add all the known publications
     */
    private void addPublication(CharSequence publication) {
        PublicationContainer container = getProject().getExtensions()
                .getByType(PublishingExtension.class)
                .getPublications();
        if (publication.toString().equals(Constant.ALL_PUBLICATIONS)) {
            // Specified Constant special string, try to apply all known Publications
            container.forEach(this::addPublication);
        } else {
            // Specified by String, try to match and get the Publication
            Publication publicationObj = container.findByName(publication.toString());
            if (publicationObj != null) {
                addPublication(publicationObj);
            } else {
                log.debug("Publication named '{}' does not exist for project '{}' in task '{}'.", publication, getProject().getPath(), getPath());
            }
        }
    }

    /**
     * Add a IvyPublication/MavenPublication to be included in this task
     * @param publicationObj - publication object to collect information from
     */
    private void addPublication(Publication publicationObj) {
        if (publicationObj instanceof IvyPublication) {
            ivyPublications.add((IvyPublication) publicationObj);
        } else if (publicationObj instanceof MavenPublication) {
            mavenPublications.add((MavenPublication) publicationObj);
        } else {
            log.warn("Publication named '{}' in project '{}' is of unknown type '{}'",
                    publicationObj.getName(), getProject().getPath(), publicationObj.getClass());
        }
    }

    /**
     * Make sure task dependsOn any IvyPublication tasks and the task of the descriptor files for the published artifacts
     */
    private void createDependencyOnIvyPublications() {
        for (IvyPublication ivyPublication : ivyPublications) {
            // TODO: Check why still need to be internal
            if (!(ivyPublication instanceof IvyPublicationInternal)) {
                log.warn("Ivy publication name '{}' is of unsupported type '{}'!",
                        ivyPublication.getName(), ivyPublication.getClass());
                continue;
            }
            // Add 'dependsOn' to collect the artifacts from the publication
            ivyPublication.getArtifacts().forEach(this::dependsOn);
            // Add 'dependsOn' the task that creates metadata/descriptors for the published artifacts
            String capitalizedPublicationName = ivyPublication.getName().substring(0, 1).toUpperCase() + ivyPublication.getName().substring(1);
            dependsOn(String.format("%s:generateDescriptorFileFor%sPublication",
                    getProject().getPath(), capitalizedPublicationName));
        }
    }

    /**
     * Make sure task dependsOn any IvyPublication tasks and the task of the pom file for the published artifacts
     */
    private void createDependencyOnMavenPublications() {
        for (MavenPublication mavenPublication : mavenPublications) {
            if (!(mavenPublication instanceof MavenPublicationInternal)) {
                log.warn("Maven publication name '{}' is of unsupported type '{}'!",
                        mavenPublication.getName(), mavenPublication.getClass());
                continue;
            }
            // Add 'dependsOn' to collect the artifacts from the publication
            mavenPublication.getArtifacts().forEach(this::dependsOn);
            // Add 'dependsOn' the task that creates metadata/descriptors for the published artifacts
            String capitalizedPublicationName = mavenPublication.getName().substring(0, 1).toUpperCase() +
                    mavenPublication.getName().substring(1);
            dependsOn(String.format("%s:generatePomFileFor%sPublication",
                    getProject().getPath(), capitalizedPublicationName));
        }
    }

    public boolean hasPublications() {
        return !ivyPublications.isEmpty() || !mavenPublications.isEmpty();
    }

    /**
     * Collect all the deployment details for this project
     */
    @TaskAction
    public void collectDeployDetails() {
        log.info("<ASSAF> Task '{}' activated", getPath());
        if (!hasPublications()) {
            log.info("No publications to publish for project '{}'", getProject().getPath());
            return;
        }
        collectDetailsFromIvyPublications();
        collectDetailsFromMavenPublications();
    }

    private void collectDetailsFromIvyPublications() {
        for (IvyPublication ivyPublication : ivyPublications) {
            String publicationName = ivyPublication.getName();
            if (!(ivyPublication instanceof IvyPublicationInternal)) {
                // TODO: Check how the descriptor file can be extracted without using asNormalisedPublication
                log.warn("Ivy publication name '{}' is of unsupported type '{}'!",
                        publicationName, ivyPublication.getClass());
                continue;
            }
            PublicationUtils.extractIvyDeployDetails((IvyPublicationInternal) ivyPublication, this);
        }
    }

    private void collectDetailsFromMavenPublications() {
        for (MavenPublication mavenPublication : mavenPublications) {
            String publicationName = mavenPublication.getName();
            if (!(mavenPublication instanceof MavenPublicationInternal)) {
                // TODO: Check how the descriptor file can be extracted without using asNormalisedPublication
                log.warn("Maven publication name '{}' is of unsupported type '{}'!",
                        publicationName, mavenPublication.getClass());
                continue;
            }
            PublicationUtils.extractMavenDeployDetails((MavenPublicationInternal) mavenPublication, this);
        }
    }

    @Input
    @Optional
    public Set<GradleDeployDetails> getDeployDetails() {
        return deployDetails;
    }

    @Input
    public Set<Publication> getPublications() {
        Set<Publication> publications = new HashSet<>();
        publications.addAll(ivyPublications);
        publications.addAll(mavenPublications);
        return publications;
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishArtifacts() { return getFlag(Constant.PUBLISH_ARTIFACTS); }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishIvy() { return getFlag(Constant.PUBLISH_IVY); }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishPom() {
        return getFlag(Constant.PUBLISH_POM);
    }

    public boolean isSkip() {
        return skip;
    }

    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    public void setPublishArtifacts(Object publishArtifacts) {
        setFlag(Constant.PUBLISH_ARTIFACTS, toBoolean(publishArtifacts));
    }



    @Internal
    public boolean isEvaluated() {
        return evaluated;
    }

    private Boolean toBoolean(Object o) {
        return Boolean.valueOf(o.toString());
    }

    @Nullable
    private Boolean getFlag(String flagName) {
        return flags.get(flagName);
    }

    private void setFlag(String flagName, Boolean newValue) {
        flags.put(flagName, newValue);
    }
}
