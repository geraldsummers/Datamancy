import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer

plugins {
    kotlin("jvm")
    application
    id("com.gradleup.shadow")
}

dependencies {
    
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.databind)

    
    implementation(libs.sshj)

    
    implementation(libs.postgres.jdbc)
    implementation(libs.mariadb.jdbc)
    implementation("com.zaxxer:HikariCP:5.1.0")

    
    implementation("com.github.jsqlparser:jsqlparser:4.9")

    
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)

    
    testImplementation(libs.bundles.ktor.client)

    
    testImplementation(libs.kotlinx.serialization.json)

    
    testImplementation(libs.bundles.logging)
}

tasks.test {

}

tasks.shadowJar {
    transform(ServiceFileTransformer::class.java)
}

application {
    mainClass.set("org.example.MainKt")
}

