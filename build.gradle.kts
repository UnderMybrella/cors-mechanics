import dev.brella.kornea.gradle.defineVersions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.0" apply false
    kotlin("plugin.serialization") version "1.8.0" apply false
    id("com.github.johnrengelman.shadow") version "7.0.0" apply false
    id("com.bmuschko.docker-remote-api") version "7.0.0" apply false

    id("dev.brella.kornea") version "1.4.1"
    //    id("com.palantir.graal") version "0.7.2" apply false
//    id("com.bmuschko.docker-java-application") version "7.0.0" apply false
}

group = "dev.brella"

defineVersions {
    ktor("2.2.1")
    kotlinxCoroutines("1.6.4")
    kotlinxSerialisation("1.4.1")

    korneaErrors("3.1.0-alpha")

    "ktornea-client-results" .. "1.2.0-alpha"

    "caffeine" .. "3.1.2"
    "logback" .. "1.4.5"
    "jq" .. "1.3.0"
}

allprojects {
    repositories {
        mavenCentral()
        maven(url = "https://maven.brella.dev")
    }
}