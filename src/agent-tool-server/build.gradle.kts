plugins {
    kotlin("jvm")
    application
    id("com.github.johnrengelman.shadow")
}

dependencies {
    testImplementation(kotlin("test"))
    // JUnit 5 API/engine for kotlin-test
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // SSH client for OpsSshPlugin
    implementation("com.hierynomus:sshj:0.37.0")
    // JDBC drivers for DataSourceQueryPlugin
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("org.example.MainKt")
}

kotlin {
    jvmToolchain(21)
}