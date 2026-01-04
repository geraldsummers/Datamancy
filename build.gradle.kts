plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.shadow) apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
    apply(plugin = "com.github.johnrengelman.shadow")

    group = "org.datamancy"
    version = "1.0-SNAPSHOT"

    dependencies {
        // Common dependencies for all subprojects can go here
    }

    // Configure JVM toolchain for all subprojects
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
    }

    // Configure test tasks for all subprojects
    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Configure shadow JAR for application modules
    tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
    }
}

// Root-level test task runs unit tests only (no Docker required)
tasks.register("test") {
    dependsOn(
        ":agent-tool-server:test",
        ":data-fetcher:test",
        ":control-panel:test",
        ":search-service:test",
        ":unified-indexer:test"
    )
    // Note: test-commons has no tests (it's a library module)
    // Note: stack-tests excluded - run separately with 'integrationTest' task
}

// Separate integration test task for Docker-dependent tests
tasks.register("integrationTest") {
    group = "verification"
    description = "Run integration tests that require Docker stack"
    dependsOn(":stack-tests:test")
}