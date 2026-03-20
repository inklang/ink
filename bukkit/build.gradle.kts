plugins {
    kotlin("jvm") version "2.2.21"
}

group = "org.inklang"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(project(":lang"))
}

kotlin {
    jvmToolchain(21)
}
