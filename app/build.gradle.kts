plugins {
    id("com.android.application")
}

val targetAbi = providers.gradleProperty("targetAbi")

android {
    namespace = "com.example.csc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.csc"
        minSdk = 29
        targetSdk = 36
        versionCode = 12
        versionName = "1.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        targetAbi.orNull?.let { abi ->
            ndk {
                abiFilters += abi
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Bundled models work immediately and do not upload screenshots.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation("junit:junit:4.13.2")
}
