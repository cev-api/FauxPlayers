plugins { base }

group = "com.cevapi.fauxplayers"
version = "1.0.1"

tasks.register("buildAllPlatforms") {
    group = "build"
    description = "Build the Paper plugin and Fabric 26.2 server mod."
    dependsOn(":paper:build", ":fabric:build")
}

tasks.named("build") {
    dependsOn("buildAllPlatforms")
}
