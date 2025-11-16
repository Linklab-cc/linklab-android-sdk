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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.okhttp)
    implementation(libs.kotlin.stdlib)
    implementation(libs.installreferrer)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
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

// Add local repository for bundle creation
afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "LocalBundle"
                url = uri("${project.layout.buildDirectory.get()}/maven-bundle")
            }
        }
    }
}

// Task to publish to local repository for bundle creation
tasks.register("publishToLocalBundle") {
    group = "publishing"
    description = "Publishes all artifacts to a local directory for bundle creation"
    dependsOn("publishReleasePublicationToLocalBundleRepository")
    doLast {
        println("Published to: ${project.layout.buildDirectory.get()}/maven-bundle")
    }
}

// Task to create deployment bundle
tasks.register<Zip>("createDeploymentBundle") {
    group = "publishing"
    description = "Creates a deployment bundle for Maven Central"
    dependsOn("publishToLocalBundle")
    
    from("${project.layout.buildDirectory.get()}/maven-bundle")
    archiveFileName.set("deployment-bundle.zip")
    destinationDirectory.set(project.layout.buildDirectory.get().asFile)
    
    doLast {
        println("Bundle created at: ${project.layout.buildDirectory.get()}/deployment-bundle.zip")
    }
}

// Task to automatically publish to Central Portal
tasks.register("publishToCentralPortal") {
    group = "publishing"
    description = "Automatically publishes to Maven Central via Central Portal API"
    dependsOn("createDeploymentBundle")
    
    doLast {
        val username = project.findProperty("ossrhUsername")?.toString() 
            ?: System.getenv("OSSRH_USERNAME") 
            ?: error("ossrhUsername not found in gradle.properties or OSSRH_USERNAME env var")
        val password = project.findProperty("ossrhPassword")?.toString() 
            ?: System.getenv("OSSRH_PASSWORD") 
            ?: error("ossrhPassword not found in gradle.properties or OSSRH_PASSWORD env var")
        
        val bundleFile = file("${project.layout.buildDirectory.get()}/deployment-bundle.zip")
        if (!bundleFile.exists()) {
            error("Bundle file not found: ${bundleFile.absolutePath}")
        }
        
        println("📦 Uploading bundle to Central Portal...")
        
        // Upload the bundle using curl
        val uploadResult = exec {
            commandLine(
                "curl", "-X", "POST",
                "https://central.sonatype.com/api/v1/publisher/upload",
                "-H", "Authorization: Bearer $username:$password",
                "-F", "bundle=@${bundleFile.absolutePath}",
                "-w", "\\n%{http_code}"
            )
            isIgnoreExitValue = true
        }
        
        if (uploadResult.exitValue == 0) {
            println("✅ Successfully uploaded to Central Portal!")
            println("🔍 Check status at: https://central.sonatype.com/publishing")
            println("⏱  Artifacts will sync to Maven Central in 15-30 minutes")
        } else {
            error("❌ Failed to upload bundle. Check your credentials and try again.")
        }
    }
}

tasks.register("releaseSonatypeRepository") {
    dependsOn(rootProject.tasks.named("closeAndReleaseSonatypeStagingRepository"))
    doLast {
        println("Closed and released Sonatype staging repository")
    }
}