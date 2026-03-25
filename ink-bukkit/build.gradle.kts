plugins {
    kotlin("jvm") version "2.2.21"
    id("io.github.goooler.shadow") version "8.1.8"
    id("xyz.jpenilla.run-paper") version "3.0.2"
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

    processResources {
        expand("version" to version)
    }

    shadowJar {
        archiveFileName.set("inklang-bukkit-${version}.jar")
        relocate("org.inklang", "org.inklang.lib")
    }

    build {
        dependsOn(shadowJar)
    }

    runServer {
        minecraftVersion("1.21.11")
    }

    register("deployScripts") {
        group = "ink"
        description = "Compile example scripts and copy to run/plugins/Ink/plugins/"
        dependsOn(":ink:jar")

        doLast {
            val inkJar = project(":ink").tasks.named("jar").get().outputs.files.singleFile
            val pluginsDir = layout.projectDirectory.dir("run/plugins/Ink/plugins").asFile
            pluginsDir.mkdirs()

            fileTree("${rootProject.projectDir}/examples").matching {
                include("*/ink-package.toml")
            }.forEach { tomlFile ->
                val packageDir = tomlFile.parentFile
                val grammarIr = File(packageDir, "dist/grammar.ir.json")
                val scriptsDir = File(packageDir, "scripts")
                if (!scriptsDir.exists() || !grammarIr.exists()) return@forEach

                exec {
                    commandLine(
                        "java", "-jar", inkJar.absolutePath,
                        "compile",
                        "--sources", scriptsDir.absolutePath,
                        "--out", pluginsDir.absolutePath,
                        "--grammar", grammarIr.absolutePath
                    )
                }
                println("Deployed scripts from ${packageDir.name}")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
