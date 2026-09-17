package org.jfrog.gradle.plugin.artifactory.utils;

import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.UnknownTaskException;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.jfrog.gradle.plugin.artifactory.ArtifactoryBuildService;
import org.jfrog.gradle.plugin.artifactory.Constant;
import org.jfrog.gradle.plugin.artifactory.extractor.DependencyExtractor;
import org.jfrog.gradle.plugin.artifactory.task.ArtifactoryTask;
import org.jfrog.gradle.plugin.artifactory.task.DeployTask;
import org.jfrog.gradle.plugin.artifactory.task.ExtractModuleTask;
import org.jfrog.gradle.plugin.artifactory.extractor.PreCollectedDependency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TaskUtils {
    private static final Logger log = LoggerFactory.getLogger(TaskUtils.class);

    /**
     * Android plugin configuration holding the platform jar the module is compiled against.
     */
    private static final String ANDROID_APIS_CONFIGURATION = "androidApis";

    /**
     * Register (deferring task creation) a task in a given project
     *
     * @param taskName        - the name (ID) of the task
     * @param taskClass       - the task class to be created
     * @param taskDescription - the task description
     * @param project         - the project to create the task inside
     * @param publishGroup    - if true this task will be added to publish, else will be added to 'other'
     * @return the taskProvider that was created in the project
     */
    private static <T extends Task> TaskProvider<T> registerTaskInProject(String taskName, Class<T> taskClass, String taskDescription, Project project, boolean publishGroup) {
        log.debug("Configuring {} task for project (is root: {}) {}", taskName, ProjectUtils.isRootProject(project), project.getPath());
        TaskProvider<T> taskProvider = project.getTasks().register(taskName, taskClass);
        taskProvider.configure(task -> {
            task.setDescription(taskDescription);
            if (publishGroup) {
                task.setGroup(Constant.PUBLISHING);
            }
        });
        return taskProvider;
    }

    /**
     * Adds to a given project a task to collect all the publications and information to be deployed.
     *
     * @param project - the module to collect information from
     * @return - taskProvider that was created for the given module
     */
    public static TaskProvider<ArtifactoryTask> addCollectDeployDetailsTask(Project project) {
        try {
            return project.getTasks().named(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, ArtifactoryTask.class);
        } catch (UnknownTaskException e) {
            log.debug("Task '{}' not found in project", Constant.ARTIFACTORY_PUBLISH_TASK_NAME);
        }
        return registerTaskInProject(Constant.ARTIFACTORY_PUBLISH_TASK_NAME, ArtifactoryTask.class, Constant.ARTIFACTORY_PUBLISH_TASK_DESCRIPTION, project, true);
    }

    /**
     * Adds a task that will run after the given collectDeployDetailsTask task and will extract module info file from the information collected.
     * An ExtractModuleTask task will be added (if not exists) to the given task's project.
     *
     * extract module task is responsible for extracting module details, artifacts and dependencies.
     * artifactoryTask is responsible for collecting deployment details from a project's publications. It figures out what needs to be deployed and how it should be deployed.
     * deploymentTask is responsible for execute the deployment of artifacts and build-info to Artifactory for the entire build.
     *
     * @param collectDeployDetailsTask - the task that will provide the information to produce the module info file
     * @param project                  - the project that the collectDeployDetailsTask is configured in to register the new task in it.
     */
    public static void addExtractModuleInfoTask(TaskProvider<ArtifactoryTask> collectDeployDetailsTask, Project project) {
        TaskProvider<ExtractModuleTask> taskProvider = null;
        // Register
        try {
            taskProvider = project.getTasks().named(Constant.EXTRACT_MODULE_TASK_NAME, ExtractModuleTask.class);
        } catch (UnknownTaskException e) {
            log.debug("Task '{}' not found in project", Constant.EXTRACT_MODULE_TASK_NAME);
        }
        if (taskProvider == null) {
            taskProvider = registerTaskInProject(Constant.EXTRACT_MODULE_TASK_NAME, ExtractModuleTask.class, Constant.EXTRACT_MODULE_TASK_DESCRIPTION, project, false);
        }
        // Lazy Configure
        taskProvider.configure(extractModuleTask -> {
            extractModuleTask.getOutputs().upToDateWhen(reuseOutputs -> false);
            extractModuleTask.getModuleFile().set(project.getLayout().getBuildDirectory().file(Constant.MODULE_INFO_FILE_NAME));
            extractModuleTask.mustRunAfter(project.getTasks().withType(ArtifactoryTask.class));

            extractModuleTask.setProjectInfo(
                    project.getPath(),
                    project.getName(),
                    project.getGroup().toString(),
                    project.getVersion().toString()
            );
        });

        TaskProvider<ExtractModuleTask> finalTaskProvider = taskProvider;
        // Wire the ExtractModuleTask output files to the DeployTask inputs
        project.getRootProject().getTasks().withType(DeployTask.class).configureEach(deployTask -> {
            deployTask.addModuleInfoFiles(finalTaskProvider.get().getOutputs().getFiles());
        });

        // Configuration-cache-compatible dependency capture: for each resolvable configuration, wire a lazy
        // provider that transforms its resolution result + resolved artifacts into serializable dependency
        // records. The transform runs at execution time and its result is stored in the configuration cache,
        // so dependencies survive a cache hit without a config-time resolution listener.
        wireDependencyProviders(project, finalTaskProvider);
    }

    /**
     * Wire a lazy dependency-capture provider onto the project's ExtractModuleTask for each classpath
     * configuration that contributes the module's published dependencies.
     * <p>
     * Enumeration is deferred to {@code gradle.projectsEvaluated}, which runs after every project's
     * {@code afterEvaluate}. A per-project {@code afterEvaluate} is too early for the Android plugin, which
     * creates its per-variant {@code <variant>CompileClasspath}/{@code <variant>RuntimeClasspath}
     * configurations in its own {@code afterEvaluate}; enumerating before that reports almost no
     * dependencies for Android modules. Enumerating does not resolve anything — each configuration resolves
     * only when ExtractModuleTask reads its provider at execution time, which is what makes this
     * configuration-cache safe (the resolution result is stored in the cache entry and reloaded on a hit).
     * <p>
     * Which configurations are selected is decided by {@link #collectDependencyClasspathNames}; wiring every
     * resolvable configuration would force-resolve configurations the build never resolves and report
     * dependencies the pre-configuration-cache implementation never collected.
     */
    private static void wireDependencyProviders(Project project, TaskProvider<ExtractModuleTask> extractTaskProvider) {
        project.getGradle().projectsEvaluated(gradle -> {
            Set<String> classpathNames = collectDependencyClasspathNames(project);
            List<Provider<List<PreCollectedDependency>>> providers = new ArrayList<>();
            project.getConfigurations().forEach(configuration -> {
                if (!configuration.isCanBeResolved() || !classpathNames.contains(configuration.getName())) {
                    return;
                }
                String configName = configuration.getName();
                ResolvableDependencies incoming = configuration.getIncoming();
                providers.add(incoming.getResolutionResult().getRootComponent().zip(
                        incoming.artifactView(view -> view.setLenient(true))
                                .getArtifacts()
                                .getResolvedArtifacts(),
                        (root, artifacts) -> DependencyExtractor.extract(configName, root, artifacts)));
            });
            extractTaskProvider.configure(extractModuleTask ->
                    providers.forEach(p -> extractModuleTask.getPreCollectedDependencies().addAll(p)));
        });
    }

    /**
     * Select the classpath configurations whose dependencies belong in the published build-info.
     * <p>
     * The pre-configuration-cache implementation read every configuration whose state was already
     * {@code RESOLVED} when the module info was extracted — that is, the configurations the build itself had
     * resolved. Selecting by resolved state is not possible any more (the state is unknown at configuration
     * time, and under a configuration-cache hit the Configuration objects no longer exist at execution
     * time), so the same dependency set is reproduced from the source sets:
     * <ul>
     *     <li>the main source set's classpaths are always included — the build resolves them for the
     *     artifacts it publishes even when the source set itself is empty;</li>
     *     <li>every other source set (notably {@code test}) contributes only when it has sources, because
     *     only then does its compile task run and resolve its classpaths. This is what makes a module with
     *     a {@code src/test} directory report its test dependencies while a module without one does not;</li>
     *     <li>each selected source set also contributes its annotation processor path, which the build
     *     resolves for the same compile task.</li>
     * </ul>
     * Configurations the Android plugin manages itself are matched by name instead, since they are not part
     * of the java {@code SourceSetContainer}: the per-variant
     * {@code <variant>CompileClasspath}/{@code <variant>RuntimeClasspath}/
     * {@code <variant>AnnotationProcessorClasspath} configurations — test variants included, as an Android
     * build resolves them — plus {@code androidApis}, which carries the compiled-against Android platform
     * jar and which the pre-configuration-cache build-info reported as a dependency.
     * <p>
     * Deliberately not selected are the tooling configurations an ordinary build leaves unresolved, notably
     * {@code androidJacocoAnt} (the JaCoCo/ASM stack) and {@code androidJdkImage} (a generated JDK image):
     * they resolve only under coverage or desugaring setups, so the old implementation never reported them,
     * and force-resolving them here would add dependencies that were never part of the build-info.
     */
    private static Set<String> collectDependencyClasspathNames(Project project) {
        Set<String> selected = new LinkedHashSet<>();
        Set<String> sourceSetOwned = new LinkedHashSet<>();
        SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
        if (sourceSets != null) {
            sourceSets.forEach(sourceSet -> {
                sourceSetOwned.add(sourceSet.getCompileClasspathConfigurationName());
                sourceSetOwned.add(sourceSet.getRuntimeClasspathConfigurationName());
                sourceSetOwned.add(sourceSet.getAnnotationProcessorConfigurationName());
                boolean isMain = SourceSet.MAIN_SOURCE_SET_NAME.equals(sourceSet.getName());
                if (!isMain && sourceSet.getAllSource().isEmpty()) {
                    // No sources: the compile task never runs, so the build never resolves these classpaths.
                    return;
                }
                selected.add(sourceSet.getCompileClasspathConfigurationName());
                selected.add(sourceSet.getRuntimeClasspathConfigurationName());
                selected.add(sourceSet.getAnnotationProcessorConfigurationName());
            });
        }
        project.getConfigurations().forEach(configuration -> {
            String name = configuration.getName();
            if (sourceSetOwned.contains(name) || !isPluginManagedDependencyPath(name)) {
                return;
            }
            selected.add(name);
        });
        return selected;
    }

    /**
     * @return true for the dependency-bearing configurations a plugin manages outside the java
     * {@code SourceSetContainer}: the Android per-variant compile, runtime and annotation processor
     * classpaths, and {@code androidApis} (the Android platform jar the module compiles against).
     * Java source-set configurations are selected by source set instead and never reach this check.
     */
    private static boolean isPluginManagedDependencyPath(String configName) {
        if (ANDROID_APIS_CONFIGURATION.equals(configName)) {
            return true;
        }
        String lower = configName.toLowerCase(Locale.ROOT);
        return lower.endsWith("compileclasspath")
                || lower.endsWith("runtimeclasspath")
                || lower.endsWith("annotationprocessorclasspath");
    }

    /**
     * Adds a task to deploy the artifacts of a given project, extract information on the build and deploy it.
     *
     * @param project - project to add the task to, should be the root project
     */
    public static void addDeploymentTask(Project project) {
        try {
            project.getTasks().named(Constant.DEPLOY_TASK_NAME, DeployTask.class);
            return;
        } catch (UnknownTaskException e) {
            log.debug("Task '{}' not found in project", Constant.DEPLOY_TASK_NAME);
        }
        TaskProvider<DeployTask> taskProvider = registerTaskInProject(Constant.DEPLOY_TASK_NAME, DeployTask.class, Constant.DEPLOY_TASK_DESCRIPTION, project, false);
        taskProvider.configure(deployTask -> {
            deployTask.setRootProjectName(project.getName());
            deployTask.setGradleVersion(project.getGradle().getGradleVersion());
            deployTask.getRootBuildDirectory().set(project.getLayout().getBuildDirectory());
        });
    }

    /**
     * Configure the BuildService on all relevant tasks.
     */
    public static void configureBuildService(Project project, Provider<ArtifactoryBuildService> serviceProvider) {
        project.getTasks().withType(ArtifactoryTask.class).configureEach(task -> {
            task.getBuildServiceProperty().set(serviceProvider);
            task.usesService(serviceProvider);
        });
        project.getTasks().withType(ExtractModuleTask.class).configureEach(task -> {
            task.getBuildServiceProperty().set(serviceProvider);
            task.usesService(serviceProvider);
        });
        project.getTasks().withType(DeployTask.class).configureEach(task -> {
            task.getBuildServiceProperty().set(serviceProvider);
            task.usesService(serviceProvider);
        });
    }
}
