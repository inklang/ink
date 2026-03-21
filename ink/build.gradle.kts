plugins {
    kotlin("jvm") version "2.2.21"
    application
}

group = "org.inklang"
version = "1.0-SNAPSHOT"

application {
    mainClass.set("org.inklang.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.yaml:snakeyaml:2.2")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
