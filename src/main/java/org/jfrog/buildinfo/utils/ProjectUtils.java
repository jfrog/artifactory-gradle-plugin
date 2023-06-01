package org.jfrog.buildinfo.utils;

import org.gradle.api.Project;

public class ProjectUtils {

    public static boolean isRootProject(Project project) {
        return project.equals(project.getRootProject());
    }

    public static String getAsGavString(String group, String name, String version) {
        return group + ':' + name + ':' + version;
    }


}
