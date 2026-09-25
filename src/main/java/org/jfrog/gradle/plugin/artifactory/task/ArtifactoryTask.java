package org.jfrog.gradle.plugin.artifactory.task;

import org.apache.commons.lang3.StringUtils;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublicationContainer;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyArtifact;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.ivy.tasks.GenerateIvyDescriptor;
import org.gradle.api.publish.maven.MavenArtifact;
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
import org.jfrog.gradle.plugin.artifactory.ArtifactoryBuildService;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.dsl.ArtifactoryPluginConvention;
import org.jfrog.gradle.plugin.artifactory.dsl.PropertiesConfig;
import org.jfrog.gradle.plugin.artifactory.dsl.PublisherConfig;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleDeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.publication.IvyPublicationExtractor;
import org.jfrog.gradle.plugin.artifactory.extractor.publication.MavenPublicationExtractor;
import org.jfrog.gradle.plugin.artifactory.extractor.publication.PublicationExtractor;
import org.jfrog.gradle.plugin.artifactory.utils.ClientConfigHelper;
import org.jfrog.gradle.plugin.artifactory.utils.ExtensionsUtils;
import org.jfrog.gradle.plugin.artifactory.utils.PublicationUtils;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.xml.namespace.QName;
import java.io.File;
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
    @Internal
    public ArtifactSpecs artifactSpecs = new ArtifactSpecs();

    // Optional flags and attributes with default values
    private final Map<String, Boolean> flags = new HashMap<>();
    // Is this task initiated from a build server
    private boolean ciServerBuild = false;
    // Set the module type to a custom type, or "GRADLE" if not specified
    private String moduleType = ModuleType.GRADLE.toString();

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

    private String projectPath;
    private String projectName;
    private String projectGroup;
    private String projectVersion;
    private Map<String, String> configSnapshot;
    private Map<String, String> rootConfigSnapshot;
    private boolean hasSignTasks = false;

    // Pre-collected data for publication extractors (config time)
    private final List<ModuleMetadataInfo> moduleMetadataInfos = new ArrayList<>();
    private final List<MavenPomInfo> mavenPomInfos = new ArrayList<>();
    private final List<IvyDescriptorInfo> ivyDescriptorInfos = new ArrayList<>();

    // Publication snapshots (populated at config time, used at execution time)
    private final List<MavenPublicationData> mavenPublicationSnapshots = new ArrayList<>();
    private final List<IvyPublicationData> ivyPublicationSnapshots = new ArrayList<>();
    private final List<ArchiveConfigurationData> archiveConfigurationSnapshots = new ArrayList<>();

    // BuildService for inter-task communication
    private final Property<ArtifactoryBuildService> buildService;

    // Lazy version provider — resolved at execution time, not tracked as a configuration cache input.
    private final Property<String> projectVersionProvider;

    @Inject
    public ArtifactoryTask(ObjectFactory objectFactory) {
        this.buildService = objectFactory.property(ArtifactoryBuildService.class);
        this.projectVersionProvider = objectFactory.property(String.class);
    }

    /**
     * Lazy project version resolved at execution time.
     * Set via providers.environmentVariable() to avoid configuration cache invalidation.
     */
    @Internal
    public Property<String> getProjectVersionProvider() {
        return projectVersionProvider;
    }

    /**
     * Pre-collected info about GenerateModuleMetadata tasks.
     */
    public static class ModuleMetadataInfo {
        private final String publicationName;
        private final Class<? extends Publication> publicationType;
        private final File outputFile;

        public ModuleMetadataInfo(String publicationName, Class<? extends Publication> publicationType, File outputFile) {
            this.publicationName = publicationName;
            this.publicationType = publicationType;
            this.outputFile = outputFile;
        }

        public String getPublicationName() {
            return publicationName;
        }

        public Class<? extends Publication> getPublicationType() {
            return publicationType;
        }

        public File getOutputFile() {
            return outputFile;
        }
    }

    /**
     * Pre-collected info about GenerateMavenPom tasks.
     */
    public static class MavenPomInfo {
        private final String publicationName;
        private final File destination;

        public MavenPomInfo(String publicationName, File destination) {
            this.publicationName = publicationName;
            this.destination = destination;
        }

        public String getPublicationName() {
            return publicationName;
        }

        public File getDestination() {
            return destination;
        }
    }

    /**
     * Pre-collected info about GenerateIvyDescriptor tasks.
     */
    public static class IvyDescriptorInfo {
        private final String publicationName;
        private final File destination;

        public IvyDescriptorInfo(String publicationName, File destination) {
            this.publicationName = publicationName;
            this.destination = destination;
        }

        public String getPublicationName() {
            return publicationName;
        }

        public File getDestination() {
            return destination;
        }
    }

    /**
     * Snapshot of a MavenPublication's data.
     */
    public static class MavenPublicationData {
        private final String name;
        private final String groupId;
        private final String artifactId;
        private final String version;
        private final List<MavenArtifactData> artifacts;

        public MavenPublicationData(String name, String groupId, String artifactId, String version, List<MavenArtifactData> artifacts) {
            this.name = name;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.artifacts = artifacts;
        }

        public String getName() { return name; }
        public String getGroupId() { return groupId; }
        public String getArtifactId() { return artifactId; }
        public String getVersion() { return version; }
        public List<MavenArtifactData> getArtifacts() { return artifacts; }
    }

    public static class MavenArtifactData {
        private final File file;
        private final String extension;
        private final String classifier;

        public MavenArtifactData(File file, String extension, String classifier) {
            this.file = file;
            this.extension = extension;
            this.classifier = classifier;
        }

        public File getFile() { return file; }
        public String getExtension() { return extension; }
        public String getClassifier() { return classifier; }
    }

    /**
     * Snapshot of an IvyPublication's data.
     */
    public static class IvyPublicationData {
        private final String name;
        private final String organisation;
        private final String module;
        private final String revision;
        private final Map<QName, String> extraInfo;
        private final List<IvyArtifactData> artifacts;

        public IvyPublicationData(String name, String organisation, String module, String revision, Map<QName, String> extraInfo, List<IvyArtifactData> artifacts) {
            this.name = name;
            this.organisation = organisation;
            this.module = module;
            this.revision = revision;
            this.extraInfo = extraInfo;
            this.artifacts = artifacts;
        }

        public String getName() { return name; }
        public String getOrganisation() { return organisation; }
        public String getModule() { return module; }
        public String getRevision() { return revision; }
        public Map<QName, String> getExtraInfo() { return extraInfo; }
        public List<IvyArtifactData> getArtifacts() { return artifacts; }
    }

    public static class IvyArtifactData {
        private final File file;
        private final String name;
        private final String extension;
        private final String type;
        private final String classifier;

        public IvyArtifactData(File file, String name, String extension, String type, String classifier) {
            this.file = file;
            this.name = name;
            this.extension = extension;
            this.type = type;
            this.classifier = classifier;
        }

        public File getFile() { return file; }
        public String getName() { return name; }
        public String getExtension() { return extension; }
        public String getType() { return type; }
        public String getClassifier() { return classifier; }
    }

    /**
     * Snapshot of an archive Configuration's artifact data.
     */
    public static class ArchiveConfigurationData {
        private final String configurationName;
        private final List<ArchiveArtifactData> artifacts;

        public ArchiveConfigurationData(String configurationName, List<ArchiveArtifactData> artifacts) {
            this.configurationName = configurationName;
            this.artifacts = artifacts;
        }

        public String getConfigurationName() { return configurationName; }
        public List<ArchiveArtifactData> getArtifacts() { return artifacts; }
    }

    public static class ArchiveArtifactData {
        private final File file;
        private final String name;
        private final String extension;
        private final String type;
        private final String classifier;

        public ArchiveArtifactData(File file, String name, String extension, String type, String classifier) {
            this.file = file;
            this.name = name;
            this.extension = extension;
            this.type = type;
            this.classifier = classifier;
        }

        public File getFile() { return file; }
        public String getName() { return name; }
        public String getExtension() { return extension; }
        public String getType() { return type; }
        public String getClassifier() { return classifier; }
    }

    /**
     * Make sure this task Depends on ArtifactoryTask from all its subprojects.
     * Apply global specs and default global action to this task.
     */
    public void evaluateTask() {
        evaluated = true;
        Project project = getProject();
        if (isSkip()) {
            log.debug("'{}' skipped for project '{}'.", getPath(), project.getName());
            artifactSpecs = null;
            return;
        }

        this.projectPath = project.getPath();
        this.projectName = project.getName();
        this.projectGroup = project.getGroup().toString();
        this.projectVersion = project.getVersion().toString();

        // Snapshot configs for execution time
        ArtifactoryPluginConvention ext = ExtensionsUtils.getExtensionWithPublisher(project);
        if (ext != null) {
            this.configSnapshot = ClientConfigHelper.snapshotConfig(ext.getClientConfig());
        }
        ArtifactoryPluginConvention rootExt = ExtensionsUtils.getArtifactoryExtension(project);
        if (rootExt != null) {
            this.rootConfigSnapshot = ClientConfigHelper.snapshotConfig(rootExt.getClientConfig());
        }

        // Pre-collect sign task existence
        this.hasSignTasks = !project.getTasks().withType(Sign.class).isEmpty();

        // Pre-collect GenerateModuleMetadata info
        preCollectModuleMetadataInfo(project);
        // Pre-collect GenerateMavenPom info
        preCollectMavenPomInfo(project);
        // Pre-collect GenerateIvyDescriptor info
        preCollectIvyDescriptorInfo(project);

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
            artifactSpecs = null;
            return;
        }
        // Add global properties to the specs
        artifactSpecs = new ArtifactSpecs();
        artifactSpecs.addAll(extension.getClientConfig().publisher.getArtifactSpecs());
        // Configure the task using the "defaults" action if exists (delegate to the task)
        PublisherConfig config = extension.getPublisherConfig();
        if (config != null) {
            Action<ArtifactoryTask> defaultsAction = config.getDefaultsAction();
            if (defaultsAction != null) {
                defaultsAction.execute(this);
            }
        }

        // Snapshot publication data and clear non-serializable objects
        snapshotPublications();
        artifactSpecs = null;
    }

    /**
     * Snapshot all publication data into serializable data classes and clear the original Publication object references.
     */
    private void snapshotPublications() {
        for (MavenPublication pub : mavenPublications) {
            List<MavenArtifactData> artifacts = new ArrayList<>();
            for (MavenArtifact art : pub.getArtifacts()) {
                artifacts.add(new MavenArtifactData(art.getFile(), art.getExtension(), art.getClassifier()));
            }
            mavenPublicationSnapshots.add(new MavenPublicationData(
                    pub.getName(), pub.getGroupId(), pub.getArtifactId(), pub.getVersion(), artifacts));
        }
        for (IvyPublication pub : ivyPublications) {
            Map<QName, String> extraInfo = pub.getDescriptor().getExtraInfo().asMap();
            List<IvyArtifactData> artifacts = new ArrayList<>();
            for (IvyArtifact art : pub.getArtifacts()) {
                artifacts.add(new IvyArtifactData(art.getFile(), art.getName(), art.getExtension(), art.getType(), art.getClassifier()));
            }
            ivyPublicationSnapshots.add(new IvyPublicationData(
                    pub.getName(), pub.getOrganisation(), pub.getModule(), pub.getRevision(), extraInfo, artifacts));
        }
        for (Configuration config : archiveConfigurations) {
            List<ArchiveArtifactData> artifacts = new ArrayList<>();
            for (PublishArtifact art : config.getAllArtifacts()) {
                artifacts.add(new ArchiveArtifactData(art.getFile(), art.getName(), art.getExtension(), art.getType(), art.getClassifier()));
            }
            archiveConfigurationSnapshots.add(new ArchiveConfigurationData(config.getName(), artifacts));
        }
        // Clear non-serializable objects
        publications.clear();
        mavenPublications.clear();
        ivyPublications.clear();
        archiveConfigurations.clear();
    }

    private void preCollectModuleMetadataInfo(Project project) {
        try {
            for (GenerateModuleMetadata gmm : project.getTasks().withType(GenerateModuleMetadata.class)) {
                Publication pub = gmm.getPublication().get();
                File outputFile = gmm.getOutputFile().getAsFile().get();
                moduleMetadataInfos.add(new ModuleMetadataInfo(pub.getName(), pub.getClass(), outputFile));
            }
        } catch (Exception e) {
            log.debug("Could not pre-collect module metadata info", e);
        }
    }

    private void preCollectMavenPomInfo(Project project) {
        try {
            for (GenerateMavenPom gmp : project.getTasks().withType(GenerateMavenPom.class)) {
                // We need the pom identity to match later; store publication name from pom
                // The pom object identity matching happens at execution time via the stored file
                mavenPomInfos.add(new MavenPomInfo(gmp.getName(), gmp.getDestination()));
            }
        } catch (Exception e) {
            log.debug("Could not pre-collect maven pom info", e);
        }
    }

    private void preCollectIvyDescriptorInfo(Project project) {
        try {
            for (GenerateIvyDescriptor gid : project.getTasks().withType(GenerateIvyDescriptor.class)) {
                ivyDescriptorInfos.add(new IvyDescriptorInfo(gid.getName(), gid.getDestination()));
            }
        } catch (Exception e) {
            log.debug("Could not pre-collect ivy descriptor info", e);
        }
    }

    /**
     * Collect all the deployment details for this project
     */
    @TaskAction
    public void collectDeployDetails() {
        // Resolve lazy version provider at execution time (not a configuration cache input)
        if (projectVersionProvider.isPresent()) {
            this.projectVersion = projectVersionProvider.get();
            if (buildService.isPresent()) {
                buildService.get().setProjectVersion(this.projectVersion);
            }
        }
        log.info("Collecting deployment details in task '{}'", getPath());
        // Restore ArtifactSpecs from config snapshot (nulled before serialization)
        if (artifactSpecs == null) {
            artifactSpecs = new ArtifactSpecs();
            if (configSnapshot != null) {
                artifactSpecs.addAll(ClientConfigHelper.restoreConfig(configSnapshot).publisher.getArtifactSpecs());
            }
        }
        if (!hasPublications()) {
            log.info("No publications to publish for project '{}'", projectPath);
            return;
        }
        try {
            collectDetailsFromIvyPublications();
            collectDetailsFromMavenPublications();
            collectDetailsFromConfigurations();
        } catch (Exception e) {
            throw new RuntimeException("Cannot collect deploy details for " + getPath(), e);
        }

        // Register data with BuildService for inter-task communication
        if (buildService != null && buildService.isPresent()) {
            ArtifactoryBuildService service = buildService.get();
            service.registerTaskData(getPath(), new ArtifactoryBuildService.TaskData(
                    getPath(), projectName, projectPath,
                    deployDetails, configSnapshot,
                    moduleType, hasPublications()
            ));
        }
    }

    private void collectDetailsFromIvyPublications() {
        IvyPublicationExtractor publicationExtractor = new IvyPublicationExtractor(this);
        publicationExtractor.extractModuleInfo();
        for (IvyPublicationData data : ivyPublicationSnapshots) {
            publicationExtractor.extractDeployDetails(data);
        }
    }

    private void collectDetailsFromMavenPublications() {
        MavenPublicationExtractor publicationExtractor = new MavenPublicationExtractor(this);
        publicationExtractor.extractModuleInfo();
        for (MavenPublicationData data : mavenPublicationSnapshots) {
            MavenPublicationData effective = projectVersion != null && !projectVersion.equals(data.getVersion())
                    ? new MavenPublicationData(data.getName(), data.getGroupId(), data.getArtifactId(), projectVersion, data.getArtifacts())
                    : data;
            publicationExtractor.extractDeployDetails(effective);
        }
    }

    private void collectDetailsFromConfigurations() {
        ArtifactoryClientConfiguration.PublisherHandler publisher = getPublisherFromSnapshot();
        if (publisher == null) {
            return;
        }

        for (ArchiveConfigurationData configData : archiveConfigurationSnapshots) {
            PublicationUtils.extractArchivesDeployDetails(configData, publisher, this);
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
        return !ivyPublications.isEmpty() || !mavenPublications.isEmpty()
                || !ivyPublicationSnapshots.isEmpty() || !mavenPublicationSnapshots.isEmpty()
                || !archiveConfigurations.isEmpty() || !archiveConfigurationSnapshots.isEmpty();
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
    public Set<String> getPublicationNames() {
        Set<String> names = new HashSet<>();
        for (MavenPublicationData data : mavenPublicationSnapshots) {
            names.add(data.getName());
        }
        for (IvyPublicationData data : ivyPublicationSnapshots) {
            names.add(data.getName());
        }
        // Also include not-yet-snapshotted publications (during config time)
        for (MavenPublication pub : mavenPublications) {
            names.add(pub.getName());
        }
        for (IvyPublication pub : ivyPublications) {
            names.add(pub.getName());
        }
        return names;
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
            // Add the publisher properties from the root config snapshot
            if (rootConfigSnapshot != null) {
                ArtifactoryClientConfiguration.PublisherHandler publisher =
                        ClientConfigHelper.restoreConfig(rootConfigSnapshot).publisher;
                if (publisher != null) {
                    defaultProps.putAll(publisher.getMatrixParams());
                }
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

    @Input
    @Optional
    public String getProjectPath() {
        return projectPath;
    }

    @Input
    @Optional
    public String getProjectName() {
        return projectName;
    }

    @Input
    @Optional
    public String getProjectGroup() {
        return projectGroup;
    }

    @Input
    @Optional
    public String getProjectVersion() {
        return projectVersion;
    }

    @Input
    @Optional
    public Map<String, String> getConfigSnapshot() {
        return configSnapshot;
    }

    @Input
    @Optional
    public Map<String, String> getRootConfigSnapshot() {
        return rootConfigSnapshot;
    }

    @Input
    public boolean isHasSignTasks() {
        return hasSignTasks;
    }

    @Internal
    public List<ModuleMetadataInfo> getModuleMetadataInfos() {
        return moduleMetadataInfos;
    }

    @Internal
    public List<MavenPomInfo> getMavenPomInfos() {
        return mavenPomInfos;
    }

    @Internal
    public List<IvyDescriptorInfo> getIvyDescriptorInfos() {
        return ivyDescriptorInfos;
    }

    @Internal
    public List<MavenPublicationData> getMavenPublicationSnapshots() {
        return mavenPublicationSnapshots;
    }

    @Internal
    public List<IvyPublicationData> getIvyPublicationSnapshots() {
        return ivyPublicationSnapshots;
    }

    /**
     * Get publisher handler from the config snapshot.
     */
    @Internal
    public ArtifactoryClientConfiguration.PublisherHandler getPublisherFromSnapshot() {
        if (configSnapshot == null) {
            return null;
        }
        return ClientConfigHelper.restoreConfig(configSnapshot).publisher;
    }

    @Internal
    public Property<ArtifactoryBuildService> getBuildServiceProperty() {
        return buildService;
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