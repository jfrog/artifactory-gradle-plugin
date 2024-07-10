package org.jfrog.gradle.plugin.artifactory.task;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublicationContainer;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.ivy.tasks.GenerateIvyDescriptor;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.tasks.GenerateMavenPom;
import org.gradle.api.publish.tasks.GenerateModuleMetadata;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.*;
import org.gradle.plugins.signing.Sign;
import org.jfrog.build.api.builder.ModuleType;
import org.jfrog.build.api.multiMap.Multimap;
import org.jfrog.build.api.multiMap.SetMultimap;
import org.jfrog.build.extractor.clientConfiguration.ArtifactSpecs;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.dsl.PropertiesConfig;
import org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleDeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.publication.IvyPublicationExtractor;
import org.jfrog.gradle.plugin.artifactory.extractor.publication.MavenPublicationExtractor;
import org.jfrog.gradle.plugin.artifactory.extractor.publication.PublicationExtractor;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;
import org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Collect deploy details from publications in a project.
 * This task can also be used and configure by the user in the build script as a DSL object under 'artifactoryPublish'/'defaults' closure.
 */
public class ArtifactoryTask extends DefaultTask {
    private static final Logger log = Logging.getLogger(ArtifactoryTask.class);

    @SuppressWarnings("unused")
    public static final String ARTIFACTORY_PUBLISH_TASK_NAME = Constant.ARTIFACTORY_PUBLISH_TASK_NAME;

    // Publication containers input
    private final Set<Object> publications = new HashSet<>();
    // Properties input
    private final Multimap<String, CharSequence> properties = new SetMultimap<>();
    @Input
    public final ArtifactSpecs artifactSpecs = new ArtifactSpecs();

    // Optional flags and attributes with default values
    private final Map<String, Boolean> flags = new HashMap<>();
    // Is this task initiated from a build server
    private boolean ciServerBuild = false;
    // Set the module type to a custom type, or "GRADLE" if not specified
    private String moduleType = ModuleType.GRADLE.toString();

    @SuppressFBWarnings(value = "PA_PUBLIC_PRIMITIVE_ATTRIBUTE", justification = "Input field should be public")
    @Input
    public boolean skip = false;

    // Internal attributes
    public Set<IvyPublication> ivyPublications = new HashSet<>();
    public Set<MavenPublication> mavenPublications = new HashSet<>();
    private final Set<Configuration> archiveConfigurations = new HashSet<>();
    // This project has specified publications to the task
    private boolean publishPublicationsSpecified = false;
    @Internal
    private Map<String, String> defaultProps;
    // This project task has been evaluated
    private boolean evaluated = false;

    // Output - Container to hold all the details that were collected
    private final Set<GradleDeployDetails> deployDetails = new TreeSet<>();

    /**
     * Make sure this task Depends on ArtifactoryTask from all its subprojects.
     * Apply global specs and default global action to this task.
     */
    public void evaluateTask() {
        evaluated = true;
        Project project = getProject();
        if (isSkip()) {
            log.debug("'{}' skipped for project '{}'.", getPath(), project.getName());
            return;
        }

        // Depends on Information Collection tasks from all the subprojects
        for (Project sub : project.getSubprojects()) {
            try {
                TaskProvider<Task> subCollectInfoTask = sub.getTasks().named(Constant.ARTIFACTORY_PUBLISH_TASK_NAME);
                dependsOn(subCollectInfoTask);
            } catch (UnknownTaskException e) {
                log.debug("Can't find sub projects configured for {}", getPath());
            }
        }

        ArtifactoryPluginConvention extension = ExtensionsUtils.getExtensionWithPublisher(project);
        if (extension == null) {
            log.debug("Can't find extension configured for {}", getPath());
            return;
        }
        // Add global properties to the specs
        artifactSpecs.clear();
        artifactSpecs.addAll(extension.getClientConfig().publisher.getArtifactSpecs());
        // Configure the task using the "defaults" action if exists (delegate to the task)
        PublisherConfig config = extension.getPublisherConfig();
        if (config != null) {
            Action<ArtifactoryTask> defaultsAction = config.getDefaultsAction();
            if (defaultsAction != null) {
                defaultsAction.execute(this);
            }
        }
    }

    /**
     * Collect all the deployment details for this project
     */
    @TaskAction
    public void collectDeployDetails() {
        log.info("Collecting deployment details in task '{}'", getPath());
        if (!hasPublications()) {
            log.info("No publications to publish for project '{}'", getProject().getPath());
            return;
        }
        try {
            collectDetailsFromIvyPublications();
            collectDetailsFromMavenPublications();
            collectDetailsFromConfigurations();
        } catch (Exception e) {
            throw new RuntimeException("Cannot collect deploy details for " + getPath(), e);
        }
    }

    private void collectDetailsFromIvyPublications() {
        PublicationExtractor<IvyPublication> publicationExtractor = new IvyPublicationExtractor(this);
        publicationExtractor.extractModuleInfo();
        for (IvyPublication ivyPublication : ivyPublications) {
            publicationExtractor.extractDeployDetails(ivyPublication);
        }
    }

    private void collectDetailsFromMavenPublications() {
        PublicationExtractor<MavenPublication> publicationExtractor = new MavenPublicationExtractor(this);
        publicationExtractor.extractModuleInfo();
        for (MavenPublication mavenPublication : mavenPublications) {
            publicationExtractor.extractDeployDetails(mavenPublication);
        }
    }

    private void collectDetailsFromConfigurations() {
        ArtifactoryClientConfiguration.PublisherHandler publisher = ExtensionsUtils.getPublisherHandler(getProject());
        if (publisher == null) {
            return;
        }

        for (Configuration configuration : archiveConfigurations) {
            PublicationUtils.extractArchivesDeployDetails(configuration, publisher, this);
        }
    }

    /**
     * Task input attribute configuration to specify what publications will be included in the publication to Artifactory
     *
     * @param publications - List of entries that can be:
     *                     * Any Publication object
     *                     * String (ID of known Publication)
     *                     * 'ALL_PUBLICATIONS' a special constant that will try to include all publications if exist
     */
    public void publications(Object... publications) {
        if (publications != null) {
            this.publications.addAll(Arrays.asList(publications));
            checkDependsOnArtifactsToPublish();
        }
    }

    /**
     * Set a custom module type in the published build-info
     *
     * @param moduleType - Module type
     */
    @SuppressWarnings("unused")
    public void moduleType(String moduleType) {
        this.moduleType = moduleType;
    }

    /**
     * Extract the publications arguments for the task and make sure this task dependsOn the specified publications tasks.
     */
    private void checkDependsOnArtifactsToPublish() {
        createDependencyOnSigningTask();
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
        createDependencyOnModuleMetadata();
    }

    private void createDependencyOnSigningTask() {
        dependsOn(getProject().getTasks().withType(Sign.class));
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
                log.error("Publication type '{}' not supported in task '{}'", publication, getClass().getName());
            }
        }
        publishPublicationsSpecified = true;
    }

    /**
     * Add a Publication to the task by String ID.
     *
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
     *
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

    public void addDefaultPublications() {
        if (hasPublications()) {
            // has specified publications, no need to add default.
            if (publishPublicationsSpecified) {
                log.warn("None of the specified publications matched for project '{}' - nothing to publish.",
                        getProject().getPath());
            }
            return;
        }
        PublishingExtension publishingExtension = (PublishingExtension) getProject().getExtensions().findByName(Constant.PUBLISHING);
        if (publishingExtension == null) {
            log.warn("Can't find publishing extensions that is defined for the project {}", getProject().getPath());
            return;
        }
        addPublicationIfExists(publishingExtension, Constant.MAVEN_JAVA);
        addPublicationIfExists(publishingExtension, Constant.MAVEN_JAVA_PLATFORM);
        addPublicationIfExists(publishingExtension, Constant.MAVEN_WEB);
        addPublicationIfExists(publishingExtension, Constant.IVY_JAVA);
        // update changes
        checkDependsOnArtifactsToPublish();
    }

    public void addDefaultArchiveConfigurations() {
        Project project = getProject();
        Configuration archiveConfig = project.getConfigurations().findByName(Dependency.ARCHIVES_CONFIGURATION);
        if (archiveConfig == null) {
            log.debug("No publish configurations specified for project '{}' and the default '{}' " +
                    "configuration does not exist.", project.getPath(), Dependency.ARCHIVES_CONFIGURATION);
            return;
        }
        archiveConfigurations.add(archiveConfig);
        dependsOn(archiveConfig.getArtifacts());
    }

    private void addPublicationIfExists(PublishingExtension publishingExtension, String publicationName) {
        Publication publication = publishingExtension.getPublications().findByName(publicationName);
        if (publication != null) {
            log.info("Publication '{}' exists but not specified for '{}' - adding to task publications.",
                    getPath(), publicationName);
            addPublication(publication);
        }
    }

    /**
     * Make sure task dependsOn any IvyPublication tasks and the task of the descriptor files for the published artifacts
     */
    private void createDependencyOnIvyPublications() {
        for (IvyPublication ivyPublication : ivyPublications) {
            // Add 'dependsOn' to collect the artifacts from the publication
            dependsOn(ivyPublication.getArtifacts());
        }
        dependsOn(getProject().getTasks().withType(GenerateIvyDescriptor.class));
    }

    /**
     * Make sure task dependsOn any MavenPublication tasks and the task of the pom file for the published artifacts
     */
    private void createDependencyOnMavenPublications() {
        for (MavenPublication mavenPublication : mavenPublications) {
            // Add 'dependsOn' to collect the artifacts from the publication
            dependsOn(mavenPublication.getArtifacts());
        }
        dependsOn(getProject().getTasks().withType(GenerateMavenPom.class));
    }

    /**
     * Make sure task dependsOn only on module metadata generation tasks that have attached software components (e.g. from components.java)
     */
    public void createDependencyOnModuleMetadata() {
        try {
            Method component = GenerateModuleMetadata.class.getDeclaredMethod("component");
            component.setAccessible(true);
            for (GenerateModuleMetadata generateModuleMetadata : getProject().getTasks().withType(GenerateModuleMetadata.class)) {
                if (component.invoke(generateModuleMetadata) != null) {
                    dependsOn(generateModuleMetadata);
                }
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // Ignore
        }
    }

    public boolean hasPublications() {
        return !ivyPublications.isEmpty() || !mavenPublications.isEmpty() || !archiveConfigurations.isEmpty();
    }

    public void finalizeByDeployTask(Project project) {
        try {
            TaskProvider<Task> deployTask = project.getRootProject().getTasks().named(Constant.DEPLOY_TASK_NAME);
            finalizedBy(deployTask);
        } catch (UnknownTaskException e) {
            throw new IllegalStateException(String.format("Could not find %s in the root project", Constant.DEPLOY_TASK_NAME), e);
        }
    }

    public void properties(Action<PropertiesConfig> propertiesAction) {
        PropertiesConfig propertiesConfig = new PropertiesConfig(getProject());
        propertiesAction.execute(propertiesConfig);
        artifactSpecs.clear();
        artifactSpecs.addAll(propertiesConfig.getArtifactSpecs());
    }

    @Input
    @Optional
    public Set<GradleDeployDetails> getDeployDetails() {
        return deployDetails;
    }

    @Input
    @Optional
    public String getModuleType() {
        return moduleType;
    }

    @Input
    public Set<Publication> getPublications() {
        Set<Publication> publications = new HashSet<>();
        publications.addAll(ivyPublications);
        publications.addAll(mavenPublications);
        return publications;
    }

    @Input
    public Multimap<String, CharSequence> getProperties() {
        return properties;
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishArtifacts() {
        return getFlag(Constant.PUBLISH_ARTIFACTS);
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishIvy() {
        return getFlag(Constant.PUBLISH_IVY);
    }

    @Input
    @Optional
    @Nullable
    public Boolean getPublishPom() {
        return getFlag(Constant.PUBLISH_POM);
    }

    @Input
    public boolean isCiServerBuild() {
        return this.ciServerBuild;
    }

    @SuppressWarnings("unused")
    public ArtifactSpecs getArtifactSpecs() {
        return artifactSpecs;
    }

    public boolean isSkip() {
        return skip;
    }

    public Map<String, String> getDefaultProps() {
        if (defaultProps == null) {
            defaultProps = new HashMap<>();
            PublicationUtils.addProps(defaultProps, getProperties());
            // Add the publisher properties
            ArtifactoryClientConfiguration.PublisherHandler publisher = ExtensionsUtils.getPublisherHandler(getProject().getRootProject());
            if (publisher != null) {
                defaultProps.putAll(publisher.getMatrixParams());
            }
        }
        return defaultProps;
    }

    @SuppressWarnings("unused")
    public void setCiServerBuild() {
        this.ciServerBuild = true;
    }

    @SuppressWarnings("unused")
    public void setModuleType(String moduleType) {
        this.moduleType = moduleType;
    }

    @SuppressWarnings("unused")
    public void setSkip(boolean skip) {
        this.skip = skip;
    }

    public void setProperties(Map<String, CharSequence> props) {
        if (props == null || props.isEmpty()) {
            return;
        }
        properties.clear();
        for (Map.Entry<String, CharSequence> entry : props.entrySet()) {
            // The key cannot be lazy eval, but we keep the value as GString as long as possible
            String key = entry.getKey();
            if (StringUtils.isNotBlank(key)) {
                CharSequence value = entry.getValue();
                if (value != null) {
                    // Make sure all GString are now Java Strings for key,
                    // and don't call toString for value (keep lazy eval as long as possible)
                    // So, don't use HashMultimap this will call equals on the GString
                    this.properties.put(key, value);
                }
            }
        }
    }

    public void setPublishArtifacts(Object publishArtifacts) {
        setFlag(Constant.PUBLISH_ARTIFACTS, toBoolean(publishArtifacts));
    }

    public void setPublishPom(Object publishPom) {
        setFlag(Constant.PUBLISH_POM, toBoolean(publishPom));
    }

    public void setPublishIvy(Object publishIvy) {
        setFlag(Constant.PUBLISH_IVY, toBoolean(publishIvy));
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
