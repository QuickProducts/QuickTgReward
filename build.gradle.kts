plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.quickproducts"
version = "1.0.0"
description = "QuickTgReward - Telegram Rewards for Minecraft by QuickProducts"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
}

dependencies {
    // Paper API
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    // Telegram Bots (v10 modular)
    implementation("org.telegram:telegrambots-longpolling:10.2.0")
    implementation("org.telegram:telegrambots-client:10.2.0")
    implementation("org.telegram:telegrambots-meta:10.2.0")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // HikariCP for connection pooling
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Vault
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    // LuckPerms
    compileOnly("net.luckperms:api:5.4")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
}

tasks {
    processResources {
        val props = mapOf(
            "version" to version,
            "name" to project.name,
            "description" to project.description
        )
        filesMatching("plugin.yml") {
            expand(props)
        }
        filteringCharset = "UTF-8"
    }

    jar {
        archiveBaseName.set("QuickTgReward")
        archiveVersion.set(version.toString())
    }

    shadowJar {
        archiveBaseName.set("QuickTgReward")
        archiveClassifier.set("")
        archiveVersion.set(version.toString())
        relocate("org.telegram", "com.quickproducts.qtgreward.libs.telegram")
        relocate("org.sqlite", "com.quickproducts.qtgreward.libs.sqlite")
        relocate("com.zaxxer.hikari", "com.quickproducts.qtgreward.libs.hikari")
        // minimize can break reflective Telegram libs; keep full
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}
