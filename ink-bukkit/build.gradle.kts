plugins {
    kotlin("jvm") version "2.2.21"
    id("io.github.goooler.shadow") version "8.1.8"
}

group = "org.inklang"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(project(":ink"))
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.json:json:20231013")
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.76.0")
}

tasks {
    compileKotlin {
        compilerOptions {
            freeCompilerArgs.add("-Xcontext-receivers")
        }
    }

    shadowJar {
        archiveFileName.set("inklang-bukkit-${version}.jar")
        relocate("org.inklang", "org.inklang.lib")
    }

    build {
        dependsOn(shadowJar)
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
