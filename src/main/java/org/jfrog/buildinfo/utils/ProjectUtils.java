package org.jfrog.buildinfo.utils;

import org.gradle.api.Project;

public class ProjectUtils {

    public static boolean isRootProject(Project project) {
        return project.equals(project.getRootProject());
    }

    /**
     * Return true if at least one of the input components exists in the project.
     * @param project - The Gradle project of the task
     * @return true if at least one of the input components exists in the project.
     */
    public static boolean projectHasOneOfComponents(Project project, String... componentNames) {
        for (String componentName : componentNames) {
            if (project.getComponents().findByName(componentName) != null) {
                return true;
            }
        }
        return false;
    }

    public static String getAsGavString(String group, String name, String version) {
        return group + ':' + name + ':' + version;
    }


}
