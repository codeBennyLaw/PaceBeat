plugins {
    id("com.android.application")
}

val releaseVersionName = "1.0.0"

android {
    namespace = "com.pacebeat.app"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.pacebeat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 100
        versionName = releaseVersionName
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug")
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

tasks.configureEach {
    if (name == "assembleRelease") doLast {
        val outputDirectory = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val unsignedDefault = outputDirectory.resolve("app-release-unsigned.apk")
        val signedDefault = outputDirectory.resolve("app-release.apk")
        val source = when {
            signedDefault.exists() -> signedDefault
            unsignedDefault.exists() -> unsignedDefault
            else -> null
        }
        check(source != null) { "Release APK was not produced" }
        val versioned = outputDirectory.resolve(
            "${rootProject.name}-release-v-${releaseVersionName.replace('.', '-')}.apk"
        )
        source.copyTo(versioned, overwrite = true)
        if (source != versioned) source.delete()
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
