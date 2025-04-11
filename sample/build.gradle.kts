plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
        kotlinCompilerExtensionVersion = "1.5.8"
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
    implementation(libs.androidx.core.ktx.v1120)
    implementation(libs.androidx.lifecycle.runtime.ktx.v270)
    implementation(libs.androidx.activity.compose.v182)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    
    // Material 3
    implementation("androidx.compose.material3:material3")

    // Foundation (Border, Background, Box, Image, Scroll, shapes, animations, etc.)
    implementation("androidx.compose.foundation:foundation")

    // UI (Core Compose UI elements)
    implementation("androidx.compose.ui:ui")

    // Optional - Add full set of material icons
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // UI tooling (for previews, etc)
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.installreferrer)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Optional - Integration with activities
    implementation("androidx.activity:activity-compose:1.8.2")

    // Optional - Integration with ViewModels
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Standard components
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}