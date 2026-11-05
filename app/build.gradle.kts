plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
val releaseStorePassword = System.getenv("STORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")
val hasReleaseSigning = listOf(
  releaseKeystorePath,
  releaseStorePassword,
  releaseKeyAlias,
  releaseKeyPassword,
).all { !it.isNullOrBlank() }
val hasAnyReleaseSigning = listOf(releaseKeystorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
  .any { !it.isNullOrBlank() }
require(!hasAnyReleaseSigning || hasReleaseSigning) {
  "Release signing requires all four variables: KEYSTORE_PATH, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD"
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.sonix21.ciphershield"
    // Android's PBKDF2WithHmacSHA512 provider is available from API 26.
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(requireNotNull(releaseKeystorePath))
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("release")
    }
    debug { }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.zxing.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.kotlinx.coroutines.android)
  testImplementation(libs.androidx.core)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}
