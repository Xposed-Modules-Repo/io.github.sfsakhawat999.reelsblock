import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.sfsakhawat999.reelsblock"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.sfsakhawat999.reelsblock"
        minSdk = 27
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// The modern libxposed API (matches NexAlloy's setup):
// compile-time only, so the framework classes never ship inside the APK.
// Module metadata lives in app/src/main/resources/META-INF/xposed/.
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
