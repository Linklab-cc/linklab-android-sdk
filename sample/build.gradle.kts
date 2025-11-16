plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.compose.compiler)
}

tasks.named("preBuild") {
    dependsOn(":linklab:assemble")
}

android {
    namespace = "cc.linklab.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.potje.app.dev"
        minSdk = 21
        targetSdk = 35
        versionCode = 277
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {

        create("release") {
            keyAlias = "upload"//keystoreProperties["keyAlias"] as String
            keyPassword = "jyR_P9_e#5?5qCGL"//keystoreProperties["keyPassword"]?.toString() ?: System.getenv("ANDROID_KEYSTORE_PASS")
            storeFile = file("./keystore/upload-keystore.jks")//keystoreProperties["storeFile"]?.let { file(it.toString()) }
            storePassword = "jyR_P9_e#5?5qCGL"//keystoreProperties["storePassword"]?.toString() ?: System.getenv("ANDROID_KEY_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompilerExtension.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":linklab")) {
        isTransitive = true
    }

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    
    // Material 3
    implementation(libs.androidx.compose.material3)

    // Foundation (Border, Background, Box, Image, Scroll, shapes, animations, etc.)
    implementation(libs.androidx.compose.foundation)

    // UI (Core Compose UI elements)
    implementation(libs.androidx.compose.ui)

    // Optional - Add full set of material icons
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // UI tooling (for previews, etc)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.installreferrer)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Optional - Integration with ViewModels
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Standard components
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}