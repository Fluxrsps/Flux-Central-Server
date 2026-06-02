import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    `java-library`
    `maven-publish`
    application
    id("com.gradleup.shadow") version "8.3.6"
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass.set("dev.or2.central.CentralBootstrapKt")
}

dependencies {
    implementation(project(":openrune-central-common"))

    implementation(platform("io.ktor:ktor-bom:3.1.1"))
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-forwarded-header")
    implementation("io.ktor:ktor-server-config-yaml")

    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("io.zonky.test:embedded-postgres:2.2.2")

    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("org.yaml:snakeyaml:2.3")

    implementation("io.netty:netty-handler:4.1.118.Final")
    implementation("de.mkammerer:argon2-jvm:2.12")

    testImplementation(kotlin("test-junit5"))
    testImplementation(platform("io.ktor:ktor-bom:3.1.1"))
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.layout.projectDirectory.asFile
}

tasks.shadowJar {
    archiveFileName.set("openrune-central-server.jar")
    mergeServiceFiles()
}

/**
 * Maven `dev.or2:central-server` artifact: same merge rules as [tasks.shadowJar] but excludes
 * `:openrune-central-common` from the fat JAR; consumers resolve `dev.or2:central-common` via the POM.
 *
 * [tasks.shadowJar] remains a single self-contained JAR for Docker / `-jar` workflows.
 */
val centralServerShadowJar =
    tasks.register<ShadowJar>("centralServerShadowJar") {
        group = "build"
        description =
            "Fat JAR for Maven dev.or2:central-server (bundles third-party deps; excludes central-common)."
        archiveClassifier.set("")
        archiveBaseName.set("central-server")

        from(sourceSets.main.get().output)
        setConfigurations(listOf(project.configurations.getByName("runtimeClasspath")))

        mergeServiceFiles()
        manifest.from(tasks.shadowJar.get().manifest)

        dependencies {
            exclude(project(":openrune-central-common"))
        }
    }

tasks.build {
    dependsOn(tasks.shadowJar)
}

// Admin SPA is not built as part of Central: run `npm run build` in openrune-central-admin-web, then rebuild Central
// if you want the bundled `/admin` static files in the JAR.
val adminWebDistDir = rootProject.layout.projectDirectory.dir("openrune-central-admin-web/dist")

tasks.named<ProcessResources>("processResources") {
    val dist = adminWebDistDir.asFile
    if (dist.isDirectory) {
        from(adminWebDistDir) {
            into("static/admin")
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "openrune-central"
            pom {
                name.set("openrune-central")
                description.set(
                    "OpenRune Central (HTTP + world-server TCP link) as a library; embed in the game server or run as standalone (see application plugin / shadowJar).",
                )
            }
        }
        create<MavenPublication>("centralServer") {
            artifactId = "central-server"
            artifact(centralServerShadowJar)
            pom {
                name.set("central-server")
                description.set(
                    "Runnable OpenRune Central fat JAR for Maven: bundles third-party libraries but not " +
                        "dev.or2:central-common (declared as a runtime dependency). " +
                        "For a single self-contained JAR (e.g. Docker), use :openrune-central:shadowJar → openrune-central-server.jar.",
                )
                withXml {
                    val projectNode = asNode()
                    projectNode.appendNode("packaging", "jar")
                    val dependenciesNode = projectNode.appendNode("dependencies")
                    val d = dependenciesNode.appendNode("dependency")
                    d.appendNode("groupId", "dev.or2")
                    d.appendNode("artifactId", "central-common")
                    d.appendNode("version", project.version.toString())
                    d.appendNode("scope", "runtime")
                }
            }
        }
    }
}
