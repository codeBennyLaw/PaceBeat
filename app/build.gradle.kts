plugins {
    id("com.android.application")
}

android {
    namespace = "com.pacebeat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pacebeat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    applicationVariants.configureEach {
        val apkName = "${rootProject.name}-${name}-v-${versionName.replace('.', '-')}.apk"
        outputs.configureEach {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = apkName
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
