package org.jfrog.buildinfo.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

public class HelloWorldTask extends DefaultTask {

    public static final String TASK_NAME = "helloWorld";

    @TaskAction
    void helloWorldTask() {

        System.out.println("Hello World Task - done");
    }
}
