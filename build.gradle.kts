import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.5.30" apply false
    kotlin("plugin.serialization") version "1.5.30" apply false
    id("com.github.johnrengelman.shadow") version "7.0.0" apply false
    id("com.bmuschko.docker-remote-api") version "7.0.0" apply false

    //    id("com.palantir.graal") version "0.7.2" apply false
//    id("com.bmuschko.docker-java-application") version "7.0.0" apply false
}

group = "dev.brella"

allprojects {
    repositories {
        mavenCentral()
        maven(url = "https://maven.brella.dev")
    }
}