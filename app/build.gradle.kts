plugins { id("com.android.application") }
android {
    namespace = "dev.ryuya.galaxycamera"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "dev.ryuya.galaxycamera"
        minSdk = 36
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.2-experimental"
    }
    buildFeatures { buildConfig = true }
    buildTypes { release { isDebuggable = false; isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}
dependencies {
    implementation("com.google.android.material:material:1.14.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.register("writeRuntimeDependencyList") {
    doLast {
        val components = configurations.getByName("releaseRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id.toString() }.toSortedSet()
        val output = layout.buildDirectory.file("runtime-dependencies.txt").get().asFile
        output.parentFile.mkdirs()
        output.writeText(components.joinToString("\n") + "\n")
    }
}
