plugins {
    id("com.android.application")
}

val releaseVersionName = "1.1.0"

android {
    namespace = "com.pacebeat.app"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.pacebeat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 110
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    val apkBuildType = when (name) {
        "assembleDebug" -> "debug"
        "assembleRelease" -> "release"
        else -> return@configureEach
    }
    doLast {
        val outputDirectory = layout.buildDirectory.dir("outputs/apk/$apkBuildType").get().asFile
        val unsignedDefault = outputDirectory.resolve("app-$apkBuildType-unsigned.apk")
        val signedDefault = outputDirectory.resolve("app-$apkBuildType.apk")
        val source = when {
            signedDefault.exists() -> signedDefault
            unsignedDefault.exists() -> unsignedDefault
            else -> null
        }
        check(source != null) { "$apkBuildType APK was not produced" }
        val versioned = outputDirectory.resolve(
            "${rootProject.name}-$apkBuildType-v-${releaseVersionName.replace('.', '-')}.apk"
        )
        source.copyTo(versioned, overwrite = true)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
