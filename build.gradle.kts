import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    id("java")
    id("java-library")
    id("maven-publish")
    id("com.gradleup.shadow") version "9.3.1"
}

group = "com.willfp"
version = findProperty("version")!!

base {
    archivesName.set(project.name)
}

dependencies {
    project.project(project(":eco-core").path).subprojects {
        implementation(this)
    }
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "kotlin")
    apply(plugin = "maven-publish")
    apply(plugin = "com.gradleup.shadow")

    repositories {
        mavenCentral()
        mavenLocal()

        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.auxilor.io/repository/maven-public/")
        maven("https://jitpack.io")
        maven("https://repo.dmulloy2.net/repository/public/")
        maven("https://repo.helpch.at/releases")
    }

    dependencies {
        compileOnly("com.willfp:eco:7.6.0")
        compileOnly("org.jetbrains:annotations:26.0.2")
        compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.0")
        compileOnly("com.github.ben-manes.caffeine:caffeine:3.2.3")

        compileOnly("me.clip:placeholderapi:2.11.7")

        // Exposed ORM – bundled inside eco's server JAR (implementation dep there);
        // declared compileOnly here so the Kotlin compiler sees the types.
        compileOnly("org.jetbrains.exposed:exposed-core:1.2.0")
        compileOnly("org.jetbrains.exposed:exposed-jdbc:1.2.0")

        // HikariCP – available at runtime via the Paper library loader declaration in
        // plugin.yml; declared compileOnly so we can compile HikariConfig / HikariDataSource.
        compileOnly("com.zaxxer:HikariCP:7.0.2")
    }

    java {
        withSourcesJar()
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks {
        shadowJar {
            exclude("META-INF/**")
            relocate("kotlin", "com.willfp.eco.libs.kotlin")
            relocate("kotlin.jvm", "com.willfp.eco.libs.kotlin.jvm")
            relocate("kotlin.coroutines", "com.willfp.eco.libs.kotlin.coroutines")
            relocate("kotlin.reflect", "com.willfp.eco.libs.kotlin.reflect")
        }

        compileKotlin {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
            }
        }

        compileJava {
            options.isDeprecation = true
            options.encoding = "UTF-8"

            dependsOn(clean)
        }

        processResources {
            filesMatching(listOf("**plugin.yml")) {
                expand(
                    "version" to project.version,
                    "pluginName" to rootProject.name
                )
            }
        }

        build {
            dependsOn(shadowJar)
        }
    }
}

tasks {
    build {
        dependsOn("uberJar")
    }

    register("uberJar", Jar::class.java) {
        destinationDirectory.set(file("$rootDir/bin"))
        archiveFileName.set("${project.name} v${project.version}.jar")

        dependsOn(getByName("shadowJar"))

        from(
            getByName("shadowJar").outputs.files.map { file -> zipTree(file) }

        )
    }

    register("cleanJar") {
        doLast {
            file("$rootDir/bin").deleteRecursively()
        }
    }

    clean {
        dependsOn(getByName("cleanJar"))
    }
}