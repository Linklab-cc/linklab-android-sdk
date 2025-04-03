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

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
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

                groupId = project.findProperty("group")?.toString() ?: "cc.linklab"
                artifactId = "android"
                version = project.findProperty("version")?.toString() ?: "0.0.1-SNAPSHOT"
                
                pom {
                    name.set("LinkLab Android SDK")
                    description.set(project.findProperty("projectDescription")?.toString() ?: "Android SDK for LinkLab integration")
                    url.set(project.findProperty("projectUrl")?.toString() ?: "https://github.com/linklab/linklab-android-sdk")
                    
                    licenses {
                        license {
                            name.set(project.findProperty("projectLicenseName")?.toString() ?: "The Apache License, Version 2.0")
                            url.set(project.findProperty("projectLicenseUrl")?.toString() ?: "http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    
                    developers {
                        developer {
                            id.set(project.findProperty("projectAuthorId")?.toString() ?: "linklab")
                            name.set(project.findProperty("projectAuthorName")?.toString() ?: "LinkLab")
                            email.set(project.findProperty("projectAuthorEmail")?.toString() ?: "info@linklab.cc")
                        }
                    }
                    
                    scm {
//                        connection.set(project.findProperty("projectScmConnection")?.toString() ?: "scm:git:git://github.com/linklab/linklab-android-sdk.git")
//                        developerConnection.set(project.findProperty("projectScmDeveloperConnection")?.toString() ?: "scm:git:ssh://github.com/linklab/linklab-android-sdk.git")
                        url.set(project.findProperty("projectScmUrl")?.toString() ?: "https://github.com/linklab/linklab-android-sdk")
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

// Tasks for publishing to Maven Central
tasks.register("publishLinkLabToSonatype") {
    dependsOn("publishReleasePublicationToSonatypeRepository")
    doLast {
        println("Published to Sonatype repository")
    }
}

tasks.register("releaseSonatypeRepository") {
    dependsOn(rootProject.tasks.named("closeAndReleaseSonatypeStagingRepository"))
    doLast {
        println("Closed and released Sonatype staging repository")
    }
}