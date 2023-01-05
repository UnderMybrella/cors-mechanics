import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.github.johnrengelman.shadow")
    application
    id("com.bmuschko.docker-remote-api")
}

group = "dev.brella"
version = "2.0.0"
val latestTag = "latest"

repositories {
    mavenCentral()
    maven(url = "https://maven.brella.dev")
}

dependencies {
    testImplementation(kotlin("test-junit"))

//    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinx_serialisation_version")
//
//    implementation("io.ktor:ktor-server-netty:$ktor_version")
//    implementation("io.ktor:ktor-serialization:$ktor_version") {
//        exclude("org.jetbrains.kotlinx", "kotlinx-serialization-core")
//        exclude("org.jetbrains.kotlinx", "kotlinx-serialization-json")
//    }
//    implementation("io.ktor:ktor-websockets:$ktor_version")
//
//    implementation("io.ktor:ktor-client-cio:$ktor_version")
//    implementation("io.ktor:ktor-client-encoding:$ktor_version")
//    implementation("io.ktor:ktor-client-core-jvm:$ktor_version")
//    implementation("io.ktor:ktor-client-serialization:$ktor_version") {
//        exclude("org.jetbrains.kotlinx", "kotlinx-serialization-core")
//        exclude("org.jetbrains.kotlinx", "kotlinx-serialization-json")
//    }
//    implementation("io.ktor:ktor-client-encoding:$ktor_version")
//
//    implementation("dev.brella:ktornea-utils:1.3.3-alpha")
//
//    implementation("com.github.ben-manes.caffeine:caffeine:3.0.1")
//    implementation("ch.qos.logback:logback-classic:1.2.3")
//
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinx_coroutines_version")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:$kotlinx_coroutines_version")
//
//    implementation("com.arakelian:java-jq:1.1.0")
}

tasks.test {
    useJUnit()
}

tasks.withType<KotlinCompile>() {
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

    images.addAll("undermybrella/cors-mechanics:$version", "undermybrella/cors-mechanics:latest")
}