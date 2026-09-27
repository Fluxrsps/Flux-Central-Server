import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.component.AdhocComponentWithVariants
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    application
    `java-library`
    `maven-publish`
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
    implementation(project(":central-common"))
    implementation(project(":central-worldlink"))

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
    implementation("org.flywaydb:flyway-core:11.3.1")
    implementation("org.flywaydb:flyway-database-postgresql:11.3.1")

    implementation("com.sksamuel.hoplite:hoplite-core:2.9.0")
    implementation("com.sksamuel.hoplite:hoplite-yaml:2.9.0")

    implementation("io.insert-koin:koin-ktor:4.0.2")
    implementation("io.insert-koin:koin-logger-slf4j:4.0.2")

    implementation("io.netty:netty-handler:4.1.118.Final")
    implementation("io.zonky.test:embedded-postgres:2.2.2")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("org.reflections:reflections:0.10.2")
    implementation("net.dv8tion:JDA:5.5.1")
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
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

/**
 * Seeds exchange_items from the OSRS real-time prices API. Re-runnable; run it after a database
 * reset. Pass `--args="--overwrite"` to replace hand-tuned base prices and limits too, or
 * `--args="--f2p"` to skip members items.
 */
tasks.register<JavaExec>("importOsrsItems") {
    group = "application"
    description = "Seed Trading Post items (base prices, buy limits, alch values) from the OSRS prices API."
    mainClass.set("dev.or2.central.exchange.OsrsItemImportMain")
    classpath = sourceSets.main.get().runtimeClasspath
    workingDir = rootProject.layout.projectDirectory.asFile
}

tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("openrune-central-server.jar")
    mergeServiceFiles()
}

/**
 * Maven `dev.or2:central-server` artifact: same merge rules as [shadowJar] but excludes
 * `:central-common` and `:central-worldlink` from the fat JAR; consumers resolve them via the POM.
 *
 * [shadowJar] remains a single self-contained JAR for Docker / `-jar` workflows.
 */
val centralServerShadowJar =
    tasks.register<ShadowJar>("centralServerShadowJar") {
        group = "build"
        description =
            "Fat JAR for Maven dev.or2:central-server (bundles third-party deps; excludes central-common and central-worldlink)."
        archiveClassifier.set("")
        archiveBaseName.set("central-server")

        from(sourceSets.main.get().output)
        configurations = listOf(project.configurations.getByName("runtimeClasspath"))

        mergeServiceFiles()
        manifest.from(tasks.named<ShadowJar>("shadowJar").get().manifest)

        dependencies {
            exclude(project(":central-common"))
            exclude(project(":central-worldlink"))
        }
    }

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}

// Shadow adds a fat `-all` variant (~120MB); skip it for Maven (GitHub hosting limit is 100MB).
components.named("java", AdhocComponentWithVariants::class.java) {
    configurations.findByName("shadowRuntimeElements")?.let { shadowRuntime ->
        withVariantsFromConfiguration(shadowRuntime) {
            skip()
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
                    "OpenRune Central (HTTP + world-link) as a library; embed in the game server or run standalone.",
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
                        "dev.or2:central-common or dev.or2:central-worldlink (declared as runtime dependencies). " +
                        "For a single self-contained JAR (e.g. Docker), use :central-app:shadowJar → openrune-central-server.jar.",
                )
                withXml {
                    val projectNode = asNode()
                    projectNode.appendNode("packaging", "jar")
                    val dependenciesNode = projectNode.appendNode("dependencies")
                    fun addRuntimeDep(artifactId: String) {
                        val d = dependenciesNode.appendNode("dependency")
                        d.appendNode("groupId", "dev.or2")
                        d.appendNode("artifactId", artifactId)
                        d.appendNode("version", project.version.toString())
                        d.appendNode("scope", "runtime")
                    }
                    addRuntimeDep("central-common")
                    addRuntimeDep("central-worldlink")
                }
            }
        }
    }
}
