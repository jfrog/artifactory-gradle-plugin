package org.jfrog.buildinfo.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.jfrog.buildinfo.utils.Constant;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class CollectDeployDetailsTask extends DefaultTask {
    private static final Logger log = Logging.getLogger(CollectDeployDetailsTask.class);

    public Set<IvyPublication> ivyPublications = new HashSet<>();

    public Set<MavenPublication> mavenPublications = new HashSet<>();

    private boolean ciServerBuild = false;
    private boolean evaluated = false;

    @TaskAction
    public void taskAction() throws IOException {
        log.debug("<ASSAF> Task '{}' activated", getPath());
    }

    public void taskEvaluated() {
        Project project = getProject();

        // Depends on Information Collection from all the subprojects
        for (Project sub : project.getSubprojects()) {
            Task subCollectInfoTask = sub.getTasks().findByName(Constant.COLLECT_PUBLISH_INFO_TASK_NAME);
            if (subCollectInfoTask != null) {
                dependsOn(subCollectInfoTask);
            }
        }

        evaluated = true;
    }

    @Internal
    public boolean isEvaluated() {
        return evaluated;
    }


    public boolean hasModules() {
        return false;
    }
}
