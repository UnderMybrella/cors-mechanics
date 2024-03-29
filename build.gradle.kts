import dev.brella.kornea.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20"
    kotlin("plugin.serialization") version "1.7.20"
    id("com.github.johnrengelman.shadow") version "7.0.0"
    application

//    id("com.palantir.graal") version "0.7.2"
//    id("com.bmuschko.docker-java-application") version "7.0.0"
    id("com.bmuschko.docker-remote-api") version "7.0.0"
    id("dev.brella.kornea") version "1.4.1"
}

group = "dev.brella"
version = "1.5.0-EXPERIMENTAL"

val latestTag = "latest-experimental"

defineVersions {
    ktor("2.1.2")
    kotlinxCoroutines("1.6.4")
    kotlinxSerialisation("1.4.0")

    korneaErrors("3.1.0-alpha")

    "caffeine" .. "3.1.1"
    "logback" .. "1.4.4"
    "jq" .. "1.3.0"
}

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
    testImplementation(kotlin("test-junit"))

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
        }

        clientModules {
            implementation(cio())
            implementation(encoding())
            implementation(contentNegotiation())
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

    implementation(korneaErrorsModule())
    implementation("dev.brella:ktornea-client-results:1.2.0-alpha")
    implementation("com.sksamuel.aedile:aedile-core:1.1.2")

    implementation(versioned("com.github.ben-manes.caffeine", "caffeine"))
    implementation(versioned("ch.qos.logback:logback-classic", "logback"))

//    implementation("dev.brella:ktornea-utils:1.3.3-alpha")

    implementation(versioned("com.arakelian:java-jq", "jq"))

    implementation("dev.brella:kornea-blaseball-base:2.3.5-alpha")
    implementation("dev.brella:kornea-blaseball-api:2.3.1-alpha") {
        exclude(module = "kornea-blaseball-base")
    }
}

tasks.test {
    useJUnit()
}

tasks.withType<KotlinCompile>() {
    dependsOn(buildConstants)
    kotlinOptions.jvmTarget = "11"
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

tasks.create<com.bmuschko.gradle.docker.tasks.image.Dockerfile>("createDockerfile") {
    group = "docker"

    destFile.set(File(rootProject.buildDir, "docker/Dockerfile"))
    from("azul/zulu-openjdk-alpine:11-jre")
    label(
        mapOf(
            "org.opencontainers.image.authors" to "UnderMybrella \"undermybrella@abimon.org\""
        )
    )
    copyFile(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFileName.get(), "/app/cors-mechanics.jar")
    copyFile("application.conf", "/app/application.conf")
    entryPoint("java")
    defaultCommand("-cp", "/app/cors-mechanics.jar:/usr/lib/", "io.ktor.server.netty.EngineMain", "-config=/app/application.conf")
    exposePort(8786)
    runCommand("apk --update --no-cache add curl jq-dev")
    instruction("HEALTHCHECK CMD curl -f http://localhost:8786/health || exit 1")
}

tasks.create<Sync>("syncShadowJarArchive") {
    group = "docker"

    dependsOn("assemble")
    from(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile.get().asFile, "application.conf")
    into(tasks.named<com.bmuschko.gradle.docker.tasks.image.Dockerfile>("createDockerfile").get().destFile.get().asFile.parentFile)
}

tasks.named("createDockerfile") {
    dependsOn("syncShadowJarArchive")
}

tasks.create<com.bmuschko.gradle.docker.tasks.image.DockerBuildImage>("buildImage") {
    group = "docker"

    dependsOn("createDockerfile")
    inputDir.set(tasks.named<com.bmuschko.gradle.docker.tasks.image.Dockerfile>("createDockerfile").get().destFile.get().asFile.parentFile)

    images.addAll("undermybrella/cors-mechanics:$version", "undermybrella/cors-mechanics:$latestTag")
}

tasks.create<com.bmuschko.gradle.docker.tasks.image.DockerPushImage>("pushImage") {
    group = "docker"
    dependsOn("buildImage")

    images.addAll("undermybrella/cors-mechanics:$version", "undermybrella/cors-mechanics:$latestTag")
}