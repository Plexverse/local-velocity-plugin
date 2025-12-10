import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    java
    `java-library`
    id("com.gradleup.shadow") version "9.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.3.0-SNAPSHOT")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.docker-java:docker-java-core:3.3.4")
    implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.4")
}

group = "net.plexverse.velocityautoregister"
version = "1.0.0"
description = "Velocity Auto Register"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks {
    build {
        dependsOn(withType<ShadowJar>())
    }
    
    processResources {
        filesMatching("velocity-plugin.json") {
            filter { line ->
                line.replace("@version@", project.version.toString())
            }
        }
    }
    
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        from(sourceSets.main.get().output)
        configurations = listOf(project.configurations.runtimeClasspath.get())
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    
    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

