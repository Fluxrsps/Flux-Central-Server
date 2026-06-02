import java.io.File
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
    id("com.github.node-gradle.node") version "7.1.0" apply false
    `maven-publish`
}

val hostingDirectory: File =
    System.getenv("HOSTING_DIRECTORY")?.let { File(it) }
        ?: File("D:\\OpenRune\\openrune-hosting")

val buildNumber = System.getenv("BUILD_NUMBER") ?: "1.0"

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
                    "Aggregate POM: depend on dev.or2:central-all to pull openrune-central-common + openrune-central at the same version.",
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
                    addDep("openrune-central-common")
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

tasks.register("publishCentralStackToHosting") {
    group = "publishing"
    description =
        "Publishes openrune-central-common, openrune-central (library), and central-all POM to the hosting Maven repo."
    dependsOn(
        ":openrune-central-common:publishMavenJavaPublicationToHostingRepository",
        ":openrune-central:publishMavenJavaPublicationToHostingRepository",
        ":publishCentralAllPublicationToHostingRepository",
    )
}

tasks.register("runCentral") {
    group = "application"
    description = "Runs the standalone OpenRune Central HTTP + world-link server"
    dependsOn(":openrune-central:run")
}

tasks.register("run") {
    group = "application"
    description = "Alias for runCentral (standalone Central server)"
    dependsOn("runCentral")
}

tasks.register("shadowCentralJar") {
    group = "build"
    description = "Builds the fat openrune-central-server.jar"
    dependsOn(":openrune-central:shadowJar")
}
