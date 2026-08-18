import java.io.File
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
    `maven-publish`
}

val hostingDirectory: File =
    System.getenv("HOSTING_DIRECTORY")?.let { File(it) }
        ?: File("D:\\RSPS\\Fluxious\\hosting")

val buildNumber = System.getenv("BUILD_NUMBER") ?: "flux-3.0.0"


val centralPublishModules =
    listOf(
        ":central-common",
        ":central-worldlink",
        ":central-app",
    )

subprojects {
    repositories {
        mavenCentral()
    }
    group = "dev.or2"
    version = buildNumber

    afterEvaluate {
        extensions.findByType<PublishingExtension>()?.repositories {
            maven {
                name = "hosting"
                url = hostingDirectory.toURI()
            }
        }
    }
}

group = "dev.or2"
version = buildNumber

publishing {
    publications {
        create<MavenPublication>("centralAll") {
            groupId = "dev.or2"
            artifactId = "central-all"
            version = buildNumber
            pom {
                name.set("OpenRune Central - central-all")
                description.set(
                    "Aggregate POM: depend on dev.or2:central-all to pull dev.or2:central-common, " +
                        "dev.or2:central-worldlink, and dev.or2:openrune-central at the same version.",
                )
                url.set("https://github.com/OpenRune/OpenRune-Central-Server")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://opensource.org/licenses/Apache-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("openrune")
                        name.set("OpenRune Team")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/OpenRune/OpenRune-Central-Server.git")
                    developerConnection.set("scm:git:ssh://github.com/OpenRune/OpenRune-Central-Server.git")
                    url.set("https://github.com/OpenRune/OpenRune-Central-Server")
                }
                withXml {
                    val dependencies = asNode().appendNode("dependencies")
                    fun addDep(artifactId: String) {
                        val d = dependencies.appendNode("dependency")
                        d.appendNode("groupId", "dev.or2")
                        d.appendNode("artifactId", artifactId)
                        d.appendNode("version", buildNumber)
                        d.appendNode("scope", "compile")
                    }
                    addDep("central-common")
                    addDep("central-worldlink")
                    addDep("openrune-central")
                }
            }
        }
    }
    repositories {
        maven {
            name = "hosting"
            url = hostingDirectory.toURI()
        }
    }
}

tasks.named("publishToMavenLocal") {
    group = "publishing"
    description =
        "Publishes dev.or2:central-common, dev.or2:central-worldlink, dev.or2:openrune-central, and dev.or2:central-all to ~/.m2."
    dependsOn(centralPublishModules.map { "$it:publishMavenJavaPublicationToMavenLocal" })
}

tasks.register("publishToHosting") {
    group = "publishing"
    description =
        "Publishes dev.or2:central-common, dev.or2:central-worldlink, and dev.or2:openrune-central " +
            "(thin library JARs + sources only) to the hosting Maven repo ($hostingDirectory). " +
            "Does not publish central-all, central-server, or fat -all JARs."
    dependsOn(centralPublishModules.map { "$it:publishMavenJavaPublicationToHostingRepository" })
}

// Backward-compatible aliases (old task names from prior Gradle scripts / IDE run configs).
tasks.register("publishCentralStackToMavenLocal") {
    dependsOn(tasks.named("publishToMavenLocal"))
}
tasks.register("publishCentralStackToHosting") {
    dependsOn(
        tasks.named("publishToHosting"),
        ":central-app:publishCentralServerPublicationToHostingRepository",
        ":publishCentralAllPublicationToHostingRepository",
    )
}
tasks.register("publishCentralCommonToHosting") {
    group = "publishing"
    description = "Publishes only dev.or2:central-common to the hosting Maven repo."
    dependsOn(":central-common:publishMavenJavaPublicationToHostingRepository")
}
tasks.register("publishCentralServerToHosting") {
    group = "publishing"
    description =
        "Publishes only dev.or2:central-server (fat JAR; central-common and central-worldlink are POM dependencies, not embedded) to the hosting Maven repo."
    dependsOn(":central-app:publishCentralServerPublicationToHostingRepository")
}

val visiblePublishTasks = setOf("publishToMavenLocal", "publishToHosting", "publishCentralServerToHosting")

gradle.projectsEvaluated {
    allprojects {
        tasks.configureEach {
            if (project === rootProject && name in visiblePublishTasks) {
                return@configureEach
            }
            val hide =
                name == "publish" ||
                    name == "publishToMavenLocal" ||
                    name == "publishAllPublicationsToHostingRepository" ||
                    (name.startsWith("publish") && name.contains("Publication")) ||
                    name.startsWith("generateMetadataFileFor") ||
                    name.startsWith("generatePomFileFor")
            if (hide) {
                group = null
            }
        }
    }
}

tasks.register("runCentral") {
    group = "application"
    description = "Runs the standalone OpenRune Central server"
    dependsOn(":central-app:run")
}

tasks.register("shadowCentralJar") {
    group = "build"
    description = "Builds the fat openrune-central-server.jar"
    dependsOn(":central-app:shadowJar")
}
