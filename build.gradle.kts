plugins {
    id("org.gradle.java-library")
}

repositories {
    mavenLocal()
    mavenCentral()
}

allprojects {
    java {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(11))
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

group = "com.eventchanger"
version = "1.0.0"
description = "EventConfigChanger"

dependencies {
    compileOnly(files(
        "libs/DarkBot.jar",
        "c:/Users/Bruno/Desktop/DarkOrbit/DarkBot.jar",
        System.getenv("DARKBOT_PATH") ?: "libs/DarkBot.jar"
    ))
}

tasks.jar {
    archiveFileName.set("EventConfigChanger.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(sourceSets.main.get().output)

    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    exclude("META-INF/SIG-*")
}