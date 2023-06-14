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
val fileSpecsVersion = "1.1.2"
val commonsLangVersion = "3.12.0"
val commonsIoVersion = "2.11.0"
val commonsTxtVersion = "1.10.0"
val testNgVersion = "7.7.1"

dependencies {
    implementation ("org.jfrog.buildinfo","build-info-extractor",buildInfoVersion)
    implementation ("org.jfrog.buildinfo","build-info-api",buildInfoVersion)
    implementation ("org.jfrog.buildinfo","build-info-client",buildInfoVersion)
    implementation ("org.jfrog.filespecs","file-specs-java",fileSpecsVersion)

    implementation ("org.apache.commons", "commons-lang3",commonsLangVersion)
    implementation ("org.apache.ivy", "ivy","2.5.1")
    implementation ("com.google.guava", "guava","31.1-jre")

    testImplementation ("org.testng","testng",testNgVersion)

    "functionalTestImplementation" ("org.jfrog.buildinfo","build-info-extractor",buildInfoVersion)
    "functionalTestImplementation" ("org.jfrog.buildinfo","build-info-api",buildInfoVersion)
    "functionalTestImplementation" ("org.jfrog.filespecs","file-specs-java",fileSpecsVersion)

    "functionalTestImplementation" ("org.testng","testng",testNgVersion)
    "functionalTestImplementation" ("org.apache.commons", "commons-lang3",commonsLangVersion)
    "functionalTestImplementation" ("org.apache.commons", "commons-text",commonsTxtVersion)
    "functionalTestImplementation" ("commons-io", "commons-io",commonsIoVersion)
    "functionalTestImplementation" ("org.apache.httpcomponents", "httpclient","4.5.14")
    "functionalTestImplementation" (project(mapOf("path" to ":")))

}

gradlePlugin {
    isAutomatedPublishing = false
    plugins {
        create("artifactoryGradlePlugin") {
            displayName = "JFrog Artifactory Gradle Plugin"
            id = "com.jfrog.artifactory"
            description = pluginDescription
            implementationClass = "org.jfrog.gradle.plugin.artifactory.ArtifactoryPlugin"
        }
    }
    testSourceSets(functionalTest)
}

tasks.compileJava {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
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

// Build configurations
tasks.register<Jar>("uberJar") {
    archiveClassifier.set("uber")
    // Include the project classes
    from(sourceSets.main.get().output)
    // Include all dependencies
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    // Exclude META-INF files from dependencies
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}
tasks.named<Jar>("jar") {
    dependsOn("uberJar")
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
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
//    dependsOn("pluginUnderTestMetadata")
//    dependsOn("assemble")
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the functional tests."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    mustRunAfter(tasks.test)
//    resources = file("f")
//    resources {
//        srcDir file('build/pluginUnderTestMetadata')
//    }
}

tasks.check {
    dependsOn(functionalTestTask)
}