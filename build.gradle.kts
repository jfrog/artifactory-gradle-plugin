group = "org.jfrog.buildinfo"

val pluginDescription = "JFrog Gradle plugin publishes artifacts to Artifactory and handles the collection and publishing of Build Info."
val functionalTest by sourceSets.creating

plugins {
    `java-gradle-plugin`
    `maven-publish`
    signing
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
    implementation("org.jfrog.buildinfo", "build-info-extractor", buildInfoVersion)
    implementation("org.jfrog.buildinfo", "build-info-api", buildInfoVersion)
    implementation("org.jfrog.buildinfo", "build-info-client", buildInfoVersion)
    implementation("org.jfrog.filespecs", "file-specs-java", fileSpecsVersion)

    implementation("org.apache.commons", "commons-lang3", commonsLangVersion)
    implementation("org.apache.ivy", "ivy", "2.5.1")
    implementation("com.google.guava", "guava", "32.0.1-jre")

    testImplementation("org.testng", "testng", testNgVersion)
    testImplementation("org.mockito", "mockito-core", "3.+")

    "functionalTestImplementation"("org.jfrog.buildinfo", "build-info-extractor", buildInfoVersion)
    "functionalTestImplementation"("org.jfrog.buildinfo", "build-info-api", buildInfoVersion)
    "functionalTestImplementation"("org.jfrog.filespecs", "file-specs-java", fileSpecsVersion)

    "functionalTestImplementation"("org.testng", "testng", testNgVersion)
    "functionalTestImplementation"("org.apache.commons", "commons-lang3", commonsLangVersion)
    "functionalTestImplementation"("org.apache.commons", "commons-text", commonsTxtVersion)
    "functionalTestImplementation"("commons-io", "commons-io", commonsIoVersion)
    "functionalTestImplementation"("org.apache.httpcomponents", "httpclient", "4.5.14")
    "functionalTestImplementation"(project(mapOf("path" to ":")))

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
    sourceCompatibility = "11"
    targetCompatibility = "11"
}

// Build configurations
val sourcesJar by tasks.registering(Jar::class) {
    from(sourceSets.main.get().allJava)
    archiveClassifier.set("sources")
}

val javadocJar by tasks.registering(Jar::class) {
    from(tasks.named("javadoc"))
    archiveClassifier.set("javadoc")
}

val uberJar by tasks.register<Jar>("uberJar") {
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

publishing {
    val publication = publications.create<MavenPublication>("mavenJava") {
        artifactId = project.name

        artifact(javadocJar)
        artifact(sourcesJar)
        artifact(uberJar)

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
            scm {
                connection.set("scm:git:git://github.com/jfrog/artifactory-gradle-plugin.git")
                developerConnection.set("scm:git:git@github.com/jfrog/artifactory-gradle-plugin.git")
                url.set("https://github.com/jfrog/artifactory-gradle-plugin")
            }
        }
        from(components["java"])
    }

    extensions.configure(SigningExtension::class.java) {
        isRequired = project.hasProperty("sign")
        val signingKey = findProperty("signingKey") as String?
        val signingPassword = findProperty("signingPassword") as String?
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publication)
    }
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
