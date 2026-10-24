plugins {
    id("fabric-loom") version "1.18-SNAPSHOT"
    id("maven-publish")
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricApiVersion = providers.gradleProperty("fabric_api_version").get()

base {
    archivesName = providers.gradleProperty("archives_base_name").get()
    version = providers.gradleProperty("mod_version").get()
    group = providers.gradleProperty("maven_group").get()
}

repositories {
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("fabric.mod.json") {
            expand(props)
        }
    }

    withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
    }
}
