import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    

    
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    
    implementation(libs.bundles.ktor.server)


    implementation(libs.qdrant.client)
    implementation(libs.protobuf.java)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    runtimeOnly("io.grpc:grpc-core:1.58.0")
    runtimeOnly("io.grpc:grpc-netty-shaded:1.58.0")

    implementation(libs.guava)

    
    implementation(libs.postgres.jdbc)

    
    implementation("com.zaxxer:HikariCP:5.1.0")

    
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    
    implementation(libs.okhttp)

    
    implementation(libs.gson)

    
    implementation(libs.bundles.logging)

    
    testImplementation(libs.bundles.testing)
    testImplementation(libs.bundles.testcontainers)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("org.datamancy.searchservice.MainKt")
}


tasks.shadowJar {
    mergeServiceFiles()
    transform(ServiceFileTransformer::class.java)
}

tasks.withType(Jar::class) {
    archiveBaseName.set("search-service")
}

tasks.test {

}

