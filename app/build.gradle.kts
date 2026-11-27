plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ./gradlew assembleDebug -PproxyUrl=https://menchant-proxy.<ваш-субдомен>.workers.dev -PappToken=xxx
val proxyUrl: String = (project.findProperty("proxyUrl") as String?)
    ?: "https://menchant-proxy.example.workers.dev"
val appToken: String = (project.findProperty("appToken") as String?) ?: ""

android {
    namespace = "com.menchant.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.menchant.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "PROXY_URL", "\"$proxyUrl\"")
        buildConfigField("String", "APP_TOKEN", "\"$appToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
