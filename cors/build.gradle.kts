import dev.brella.kornea.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.bmuschko.gradle.docker.tasks.image.*

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow")
    application
    id("com.bmuschko.docker-remote-api")
}

group = "dev.brella"
version = "2.0.1"
val latestTag = "latest-modular"

val buildConstants = registerBuildConstantsTask("buildConstants") {
    setOutputInSourceSet(kotlinSourceSet(sourceSets.main))

    gitCommitShortHash("GIT_COMMIT_SHORT_HASH")
    gitCommitHash("GIT_COMMIT_LONG_HASH")
    gitBranch("GIT_BRANCH")
    gitCommitMessage("GIT_COMMIT_MESSAGE")
    gradleVersion("GRADLE_VERSION")
    gradleGroup("GRADLE_GROUP")
    gradleName("GRADLE_NAME")
    gradleDisplayName("GRADLE_DISPLAY_NAME")
    gradleDescription("GRADLE_DESCRIPTION")
    buildTimeEpoch("BUILD_TIME_EPOCH")
    buildTimeUtcEpoch("BUILD_TIME_UTC_EPOCH")

    add("TAG", latestTag)
}

repositories {
    mavenCentral()
    maven(url = "https://maven.brella.dev")
}

dependencies {
    ktorModules {
        serverModules {
            implementation(netty())
            implementation(compression())
            implementation(contentNegotiation())
            implementation(cors())
            implementation(conditionalHeaders())
            implementation(statusPages())
            implementation(doubleReceive())
            implementation(callId())
            implementation(callLogging())
            implementation(websockets())
        }

        clientModules {
            implementation(cio())
            implementation(encoding())
            implementation(contentNegotiation())
            implementation(of("websockets"))
        }

        implementation(serialisationKotlinxJson())
//        implementation(serialisationKotlinxCbor())
//        implementation(serialisationKotlinxXml())
    }

    kotlinxCoroutinesModules {
        implementation(core())
        implementation(jdk8())
    }

    kotlinxSerialisationModules {
        implementation(core())
    }

    implementation(project(":common"))
    implementation(korneaErrorsModule())
    implementation("dev.brella:ktornea-client-results:1.2.0-alpha")
    implementation("com.sksamuel.aedile:aedile-core:1.1.2")

    implementation(versioned("com.github.ben-manes.caffeine", "caffeine"))
    implementation(versioned("ch.qos.logback:logback-classic", "logback"))

//    implementation("dev.brella:ktornea-utils:1.3.3-alpha")

    implementation(versioned("com.arakelian:java-jq", "jq"))
}

tasks.test {
    useJUnit()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
    dependsOn(buildConstants)
}

//graal {
//    mainClass("io.ktor.server.netty.EngineMain")
//    outputName("cors-mechanics")
//    option("--initialize-at-build-time=\n" +
//           "org.slf4j.impl.StaticLoggerBinder\n" +
//           ",org.slf4j.LoggerFactory\n" +
//           ",ch.qos.logback.classic.Logger\n" +
//           ",ch.qos.logback.core.spi.AppenderAttachableImpl\n" +
//           ",ch.qos.logback.core.status.StatusBase\n" +
//           ",ch.qos.logback.classic.Level\n" +
//           ",ch.qos.logback.core.status.InfoStatus\n" +
//           ",ch.qos.logback.classic.PatternLayout\n" +
//           ",ch.qos.logback.core.CoreConstants")
//
//    option(" -H:+TraceClassInitialization")
//}

application {
    mainClass.set("io.ktor.server.netty.EngineMain")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
}

tasks.create<Dockerfile>("createDockerfile") {
    group = "docker"

    destFile.set(File(project.buildDir, "docker/Dockerfile"))
    from("azul/zulu-openjdk-alpine:11-jre")
    label(
        mapOf(
            "org.opencontainers.image.authors" to "UnderMybrella \"undermybrella@abimon.org\""
        )
    )
    copyFile(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFileName.get(), "/app/cors-mechanics.jar")
    copyFile("cors.conf", "/app/application.conf")
    entryPoint("java")
    defaultCommand("-cp", "/app/cors-mechanics.jar:/usr/lib/", "io.ktor.server.netty.EngineMain", "-config=/app/application.conf")
    exposePort(8786)
    runCommand("apk --update --no-cache add curl jq-dev")
    instruction("HEALTHCHECK CMD curl -f http://localhost:6060/health || exit 1")
}


tasks.create<Sync>("syncShadowJarArchive") {
    group = "docker"

    dependsOn("assemble")
    from(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile.get().asFile, rootProject.file("cors.conf"))
    into(tasks.named<com.bmuschko.gradle.docker.tasks.image.Dockerfile>("createDockerfile").get().destFile.get().asFile.parentFile)
}

tasks.named("createDockerfile") {
    dependsOn("syncShadowJarArchive")
}

tasks.create<DockerBuildImage>("buildImage") {
    group = "docker"

    dependsOn("createDockerfile")
    inputDir.set(tasks.named<com.bmuschko.gradle.docker.tasks.image.Dockerfile>("createDockerfile").get().destFile.get().asFile.parentFile)

    images.addAll("undermybrella/cors-mechanics:$version", "undermybrella/cors-mechanics:$latestTag")
}

tasks.create<DockerPushImage>("pushImage") {
    group = "docker"

    dependsOn("buildImage")
    images.addAll("undermybrella/cors-mechanics:$version", "undermybrella/cors-mechanics:$latestTag")

//    registryCredentials {
//        username.set(System.getenv("DOCKERHUB_USERNAME"))
//        password.set(System.getenv("DOCKERHUB_PASSWORD"))
//    }
}