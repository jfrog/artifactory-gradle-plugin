group = "org.jfrog.buildinfo"
val descriptionValue = "JFrog Gradle plugin for Build Info extraction and Artifactory publishing."

plugins {
    `java-gradle-plugin`
    `maven-publish`
    // version 5.0+ only supported in Gradle v7.0+
    id("com.github.spotbugs") version "4.8.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    testImplementation("org.testng:testng:7.7.1")
}

gradlePlugin {
    isAutomatedPublishing = false
    plugins {
        create("artifactoryGradlePlugin") {
            displayName = "JFrog Artifactory Gradle Plugin"
            id = "com.jfrog.artifactory"
            description = descriptionValue
            implementationClass = "org.jfrog.buildinfo.ArtifactoryPlugin"
        }
    }
}

tasks {
    test {
        useTestNG()
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            pom {
                name.set(rootProject.name)
                description.set(descriptionValue)

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

spotbugs {
    ignoreFailures.set(false)
    showProgress.set(true)
    showStackTraces.set(true)
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportsDir.set(file("$buildDir/reports/spotbugs"))
}

tasks.compileJava {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

java {
    withJavadocJar()
    withSourcesJar()
}