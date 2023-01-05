import dev.brella.kornea.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.brella"
version = "2.0.0"
val latestTag = "latest"

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
    kotlinxCoroutinesModules {
        implementation(core())
        implementation(jdk8())
    }

    kotlinxSerialisationModules {
        implementation(core())
    }

    implementation(korneaErrorsModule())
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