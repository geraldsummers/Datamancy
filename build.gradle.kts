plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("com.github.johnrengelman.shadow") version "8.1.1"
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
}

// Root-level test task runs tests from all subprojects
tasks.named("test") {
    dependsOn(
        ":agent-tool-server:test",
        ":data-fetcher:test",
        ":control-panel:test",
        ":search-service:test",
        ":unified-indexer:test",
        ":stack-tests:test"
    )

    // Note: test-commons has no tests (it's a library module)
}