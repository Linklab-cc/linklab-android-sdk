// build.gradle.kts for the library module
plugins {
    id("com.android.library")
    id("kotlin-android")
    id("maven-publish")
    id("signing")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cc.linklab.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
    implementation("androidx.compose.runtime:runtime:1.7.0")
    implementation("androidx.compose.ui:ui:1.7.0")
    implementation("com.android.installreferrer:installreferrer:2.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// For publishing the library
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "cc.linklab"
                artifactId = "android"
                version = "0.0.1-SNAPSHOT"
                
                pom {
                    name.set("LinkLab Android SDK")
                    description.set("Android SDK for LinkLab integration")
                    url.set("https://github.com/linklab/linklab-android-sdk")
                    
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    
                    developers {
                        developer {
                            id.set("linklab")
                            name.set("LinkLab")
                            email.set("dev@linklab.cc")
                        }
                    }
                    
                    scm {
                        connection.set("scm:git:git://github.com/linklab/linklab-android-sdk.git")
                        developerConnection.set("scm:git:ssh://github.com/linklab/linklab-android-sdk.git")
                        url.set("https://github.com/linklab/linklab-android-sdk")
                    }
                }
            }
        }
    }

    // Set up signing
    signing {
        sign(publishing.publications["release"])
    }
}