import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.3.10"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // No runtime deps; pure Gradle API
}

gradlePlugin {
    plugins {
        create("mcdebug") {
            id = "com.mcdebug"
            implementationClass = "com.mcdebug.gradle.McDebugPlugin"
        }
    }
}
