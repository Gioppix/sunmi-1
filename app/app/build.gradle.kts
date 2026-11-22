plugins {
    id("com.android.application")
}

android {
    namespace = "app.wsprinter"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.wsprinter"
        minSdk = 21
        // Dedicated Android 7.1 Sunmi deployment; not a Play Store target.
        // Newer Android/OEM background policies need separate device verification.
        targetSdk = 28
        testInstrumentationRunner = "app.wsprinter.ProtocolInstrumentation"
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
        checkReleaseBuilds = false
    }
}

dependencies {
    // Last OkHttp line written in Java; 4.x would pull in the Kotlin standard library.
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:3.12.13")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
