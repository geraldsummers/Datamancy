import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.client.logging)

    
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    
    implementation(libs.bundles.logging)

    
    implementation(libs.postgres.jdbc)
    implementation(libs.mariadb.jdbc)

    // Redis/Valkey client
    implementation("redis.clients:jedis:5.1.0")

    

    
    testImplementation(libs.bundles.testing)
    testImplementation("io.ktor:ktor-client-mock:3.0.2")
}

tasks.shadowJar {
    transform(ServiceFileTransformer::class.java)
}

application {
    mainClass.set("org.datamancy.testrunner.MainKt")
}
