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
    
    implementation(libs.guava)

    
    implementation("org.postgresql:postgresql:42.7.1")

    
    implementation("com.zaxxer:HikariCP:5.1.0")

    
    implementation("org.jetbrains.exposed:exposed-core:0.47.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.47.0")

    
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
}

tasks.withType(Jar::class) {
    archiveBaseName.set("search-service")
}

tasks.test {

}


