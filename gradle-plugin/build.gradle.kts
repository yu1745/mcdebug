import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.3.10"
}

group = providers.gradleProperty("group").orNull ?: rootProject.property("maven_group") as String
version = providers.gradleProperty("version").orNull ?: rootProject.property("mod_version") as String

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
    // Gson for inline JSON-RPC over TCP — avoids hand-rolling a JSON parser
    // (the request payload is filterable user input, needs proper escaping).
    implementation("com.google.code.gson:gson:2.10.1")
}

gradlePlugin {
    plugins {
        create("mcdebug") {
            id = "com.mcdebug"
            implementationClass = "com.mcdebug.gradle.McDebugPlugin"
        }
    }
}
