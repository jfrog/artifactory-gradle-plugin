val groupVal = "org.jfrog.buildinfo"
val pluginDescription = "JFrog Gradle plugin publishes artifacts to Artifactory and handles the collection and publishing of Build Info."
val functionalTest by sourceSets.creating

group = groupVal

plugins {
    id("com.gradle.plugin-publish") version "1.2.1"
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0-rc-1"
    id("signing")
    id("com.github.spotbugs-base") version "4.8.0"
    id("org.gradle.java-gradle-plugin")
}

repositories {
    mavenCentral()
}

val buildInfoVersion = "2.41.22"
val fileSpecsVersion = "1.1.2"
val commonsLangVersion = "3.12.0"
val commonsIoVersion = "2.11.0"
val commonsTxtVersion = "1.10.0"
val testNgVersion = "7.5.1"
val httpclientVersion = "4.5.14"
val spotBugsVersion = "4.8.1"

tasks.compileJava {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

java {
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    api("org.jfrog.buildinfo", "build-info-extractor", buildInfoVersion)
    api("org.jfrog.buildinfo", "build-info-api", buildInfoVersion)
    api("org.jfrog.buildinfo", "build-info-client", buildInfoVersion)
    api("org.jfrog.filespecs", "file-specs-java", fileSpecsVersion)

    implementation("org.apache.commons", "commons-lang3", commonsLangVersion)
    implementation("org.apache.ivy", "ivy", "2.5.2")

    // Dependencies that are used by the build-info dependencies and need to be included in the UberJar
    implementation("com.fasterxml.jackson.core", "jackson-databind", "2.14.1")
    implementation("commons-io", "commons-io", commonsIoVersion)
    implementation("org.apache.httpcomponents", "httpclient", httpclientVersion)

    testImplementation("org.testng", "testng", testNgVersion)
    testImplementation("org.mockito", "mockito-core", "3.+")

    "functionalTestImplementation"("org.jfrog.buildinfo", "build-info-extractor", buildInfoVersion)
    "functionalTestImplementation"("org.jfrog.buildinfo", "build-info-api", buildInfoVersion)
    "functionalTestImplementation"("org.jfrog.filespecs", "file-specs-java", fileSpecsVersion)
    "functionalTestImplementation"(gradleTestKit())

    "functionalTestImplementation"("org.testng", "testng", testNgVersion)
    "functionalTestImplementation"("org.apache.commons", "commons-lang3", commonsLangVersion)
    "functionalTestImplementation"("org.apache.commons", "commons-text", commonsTxtVersion)
    "functionalTestImplementation"("commons-io", "commons-io", commonsIoVersion)
    "functionalTestImplementation"("org.apache.httpcomponents", "httpclient", httpclientVersion)
    "functionalTestImplementation"(project(mapOf("path" to ":")))

    // Static code analysis
    spotbugs("com.github.spotbugs", "spotbugs", spotBugsVersion)
    implementation("com.github.spotbugs", "spotbugs-annotations", spotBugsVersion)
}

gradlePlugin {
    website.set("https://github.com/jfrog/artifactory-gradle-plugin")
    vcsUrl.set("https://github.com/jfrog/artifactory-gradle-plugin")
    testSourceSets(functionalTest)
    
    plugins {
        create("artifactoryGradlePlugin") {
            id = "com.jfrog.artifactory"
            implementationClass = "org.jfrog.gradle.plugin.artifactory.ArtifactoryPlugin"
            displayName = "JFrog Artifactory Gradle Plugin"
            description = pluginDescription
            tags.set(listOf("JFrog", "publication", "Artifactory", "build-info"))
        }
    }
}

val uberJar by tasks.register<Jar>("uberJar") {
    archiveClassifier.set("uber")
    // Include the project classes
    from(sourceSets.main.get().output)
    // Include all dependencies
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter {
            it.name.endsWith(".jar")
                    && !it.name.contains("gradle") && !it.name.contains("groovy")
        }.map { zipTree(it) }
    })
    // Exclude META-INF files from dependencies
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

tasks.named<Jar>("jar") {
    dependsOn(uberJar)
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
}

tasks {
    withType<JavaCompile> {
        options.compilerArgs.add("-Xlint:unchecked")
    }
}

tasks.matching { task -> task.name.contains("PluginMarker") }.configureEach {
    enabled = false
}

publishing.publications.withType<MavenPublication>().configureEach {
    artifact(uberJar)
    pom {
        name.set(rootProject.name)
        description.set(pluginDescription)
        url.set("https://github.com/jfrog/artifactory-gradle-plugin")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("JFrog")
                name.set("JFrog")
                email.set("eco-system@jfrog.com")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/jfrog/artifactory-gradle-plugin.git")
            developerConnection.set("scm:git:git@github.com/jfrog/artifactory-gradle-plugin.git")
            url.set("https://github.com/jfrog/artifactory-gradle-plugin")
        }
    }
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

signing {
    isRequired = project.hasProperty("sign")
    val signingKey = findProperty("signingKey") as String?
    val signingPassword = findProperty("signingPassword") as String?
    useInMemoryPgpKeys(signingKey, signingPassword)
}

// Javadoc configuration
tasks.withType<Javadoc> {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
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

configurations {
    "functionalTestImplementation" {
        extendsFrom(configurations["testImplementation"])
    }
}

val functionalTestTask = tasks.register<Test>("functionalTest") {
    description = "Runs the functional tests."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    mustRunAfter(tasks.test)

    // Exclude the PluginAndroidTest class
    exclude("**/PluginCiPublishTest.class")
}

tasks.check {
    dependsOn(functionalTestTask)
}

tasks.register<com.github.spotbugs.snom.SpotBugsTask>("spotBugs") {
    classDirs = files(sourceSets.main.get().output)
    sourceDirs = files(sourceSets.main.get().allSource.srcDirs)
    auxClassPaths = files(sourceSets.main.get().compileClasspath)

    reports {
        create("text") {
            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main/spotbugs.txt"))
        }
        create("html") {
            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main/spotbugs.html"))
            setStylesheet("fancy-hist.xsl")
        }
        create("xml") {
            outputLocation.set(layout.buildDirectory.file("reports/spotbugs/main/spotbugs.xml"))
        }
    }
    excludeFilter.set(
        file("${projectDir}/spotbugs-filter.xml")
    )
}
