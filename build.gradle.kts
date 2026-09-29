plugins { base }

group = "com.cevapi.fauxplayers"
version = "1.0.3"

tasks.register("buildAllPlatforms") {
    group = "build"
    description = "Build Paper and Fabric artifacts for supported Minecraft versions."
    dependsOn(":paper:build", ":fabric:build", ":fabric26_3:build")
}

tasks.named("build") {
    dependsOn("buildAllPlatforms")
}
