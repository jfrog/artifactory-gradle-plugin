group = "org.jfrog.buildinfo"

val pluginDescription = "JFrog Gradle plugin for Build Info extraction and Artifactory publishing."
val functionalTest by sourceSets.creating



plugins {
    `java-gradle-plugin`
    `maven-publish`
}

repositories {
    mavenCentral()
}

val buildInfoVersion = "2.39.8"
dependencies {
    implementation ("org.jfrog.buildinfo","build-info-extractor",buildInfoVersion)
    implementation ("org.jfrog.buildinfo","build-info-api",buildInfoVersion)
    implementation ("org.jfrog.buildinfo","build-info-client",buildInfoVersion)
    implementation("org.jfrog.filespecs","file-specs-java","1.1.2")

    implementation("org.apache.commons", "commons-lang3","3.12.0")
    implementation("org.apache.ivy", "ivy","2.5.1")
    implementation("com.google.guava", "guava","31.1-jre")

    testImplementation("org.testng:testng:7.7.1")
    "functionalTestImplementation"("org.testng:testng:7.7.1")
}

gradlePlugin {
    isAutomatedPublishing = false
    plugins {
        create("artifactoryGradlePlugin") {
            displayName = "JFrog Artifactory Gradle Plugin"
            id = "com.jfrog.artifactory"
            description = pluginDescription
            implementationClass = "org.jfrog.buildinfo.ArtifactoryPlugin"
        }
    }
    testSourceSets(functionalTest)
}

tasks.compileJava {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

// Tests configurations
tasks.withType<Test>().configureEach {
    useTestNG {
        useDefaultListeners(true)
    }
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("started", "passed", "skipped", "failed", "standardOut", "standardError")
        minGranularity = 0
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the functional tests."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    mustRunAfter(tasks.test)
}

tasks.check {
    dependsOn(functionalTestTask)
}

// Publish configurations
java {
    withJavadocJar()
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            pom {
                name.set(rootProject.name)
                description.set(pluginDescription)

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        name.set("JFrog")
                        email.set("eco-system@jfrog.com")
                    }
                }
            }
            from(components["java"])
        }
    }
}