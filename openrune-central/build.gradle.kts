import org.gradle.api.component.AdhocComponentWithVariants
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
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("net.dv8tion:JDA:5.5.1")

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

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.shadowJar {
    archiveFileName.set("openrune-central-server.jar")
    mergeServiceFiles()
}

// Hosting Maven uses thin library JARs only. Shadow stays local (Docker / GitHub Releases via shadowCentralJar).
components.named("java", AdhocComponentWithVariants::class.java) {
    withVariantsFromConfiguration(configurations.getByName("shadowRuntimeElements")) {
        skip()
    }
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
                    "OpenRune Central (HTTP + world-server TCP link) as a library; embed in the game server.",
                )
            }
        }
        create<MavenPublication>("centralServer") {
            from(components["java"])
            artifactId = "central-server"
            pom {
                name.set("central-server")
                description.set(
                    "Runnable OpenRune Central thin JAR for Maven; runtime dependencies (including dev.or2:central-common) " +
                        "are resolved via the POM, not bundled. For a local fat JAR, use :shadowCentralJar.",
                )
            }
        }
    }
}
