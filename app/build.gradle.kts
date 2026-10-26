import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val buildTimestamp: String = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())

android {
    namespace = "com.prestocast.grabador"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prestocast.grabador"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "1.2.7.1"

        buildConfigField("String", "BUILD_TIMESTAMP", "\"$buildTimestamp\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // AndroidLame JNI LAME MP3 Encoder (Excluyendo soporte heredado para evitar duplicados AndroidX)
    implementation("com.github.naman14:TAndroidLame:1.1") {
        exclude(group = "com.android.support")
    }

    debugImplementation(libs.androidx.ui.tooling)
}
