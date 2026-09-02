plugins {
    id("net.fabricmc.fabric-loom") version "1.17.20"
}

group = rootProject.group
version = rootProject.version

base {
    archivesName.set("FauxPlayers")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.3")
    // Compile against the newer 26.2 API so Loom has the complete transformed
    // classpath; the mod itself only requires the stable APIs present in 0.154.2.
    implementation("net.fabricmc.fabric-api:fabric-api:0.159.0+26.2")
    implementation(project(":common"))
    implementation("org.yaml:snakeyaml:2.6")
}

loom {
    mods {
        register("fauxplayers") {
            sourceSet(sourceSets.main.get())
            sourceSet("main", ":common")
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveClassifier.set("fabric")
    from(project(":common").sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get()
        .filter { it.name.startsWith("snakeyaml-") }
        .map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    val modVersion = project.version
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

tasks.named("build") {
    dependsOn(tasks.named("jar"))
}
