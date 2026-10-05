plugins {
    id("com.android.application")
}

android {
    namespace = "com.pacebeat.app"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.pacebeat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.0.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation("com.qmdeve.liquidglass:core:1.0.5")
    testImplementation("junit:junit:4.13.2")
}
