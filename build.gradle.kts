plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "dev.nohus"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the main class"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("SdeSchemaKt")
}
