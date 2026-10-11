plugins {
    java
    id("net.fabricmc.fabric-loom-companion") version "1.17.20"
}

group = rootProject.group
version = rootProject.version

dependencies {
    implementation("org.yaml:snakeyaml:2.6")
    implementation("com.google.code.gson:gson:2.13.1")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
