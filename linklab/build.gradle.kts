import java.io.ByteArrayOutputStream

// build.gradle.kts for the library module
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
    id("signing")
}

val sdkVersion: String = project.findProperty("version")?.toString() ?: "0.0.0"

android {
    namespace = "cc.linklab.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 21

        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
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
        freeCompilerArgs += "-Xjvm-default=all"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
    implementation(libs.okhttp)
    implementation(libs.installreferrer)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
}

// For publishing the library
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = project.findProperty("group")?.toString() ?: "cc.linklab"
                artifactId = "android"
                version = project.findProperty("version")?.toString() ?: "0.0.0"
                
                pom {
                    name.set("LinkLab Android SDK")
                    description.set(project.findProperty("projectDescription")?.toString() ?: "Android SDK for LinkLab integration")
                    url.set(project.findProperty("projectUrl")?.toString() ?: "https://github.com/Linklab-cc/linklab-android-sdk")
                    
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
                        url.set(project.findProperty("projectScmUrl")?.toString() ?: "https://github.com/Linklab-cc/linklab-android-sdk")
                    }
                }
            }
        }
    }

    // Sign only when signing credentials are supplied (-Psigning.keyId=... etc.)
    signing {
        isRequired = !project.findProperty("signing.keyId")?.toString().isNullOrBlank()
        if (isRequired) {
            sign(publishing.publications["release"])
        }
    }
}

// Add repositories for publishing
afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "LocalBundle"
                url = uri("${project.layout.buildDirectory.get()}/maven-bundle")
            }
            // Note: We use Central Portal bundle upload API for both releases and snapshots
            // No need for direct repository upload anymore
        }
    }
}

// Task to clean the bundle directory
tasks.register("cleanBundleDir") {
    group = "publishing"
    description = "Cleans the maven-bundle directory"
    doLast {
        val bundleDir = file("${project.layout.buildDirectory.get()}/maven-bundle")
        if (bundleDir.exists()) {
            bundleDir.deleteRecursively()
            logger.lifecycle("Cleaned bundle directory: ${bundleDir}")
        }
    }
}

// Task to publish to local repository for bundle creation
tasks.register("publishToLocalBundle") {
    group = "publishing"
    description = "Publishes all artifacts to a local directory for bundle creation"
    dependsOn("cleanBundleDir", "publishReleasePublicationToLocalBundleRepository")
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
        val username = project.findProperty("ossrhUsername")?.toString()?.takeIf { it.isNotBlank() }
            ?: System.getenv("OSSRH_USERNAME")?.takeIf { it.isNotBlank() }
            ?: error("ossrhUsername not provided (-PossrhUsername=... or OSSRH_USERNAME env var)")
        val password = project.findProperty("ossrhPassword")?.toString()?.takeIf { it.isNotBlank() }
            ?: System.getenv("OSSRH_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: error("ossrhPassword not provided (-PossrhPassword=... or OSSRH_PASSWORD env var)")
        
        val bundleFile = file("${project.layout.buildDirectory.get()}/deployment-bundle.zip")
        if (!bundleFile.exists()) {
            error("Bundle file not found: ${bundleFile.absolutePath}")
        }
        
        println("Uploading bundle to Central Portal...")
        
        // Upload the bundle using curl with Basic auth and capture HTTP code
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        exec {
            commandLine(
                "curl",
                "-sS",
                "-u", "$username:$password",
                "-X", "POST",
                "-H", "Accept: application/json",
                "-F", "bundle=@${bundleFile.absolutePath}",
                "-w", "\\n%{http_code}",
                "https://central.sonatype.com/api/v1/publisher/upload"
            )
            standardOutput = stdout
            errorOutput = stderr
            isIgnoreExitValue = true
        }
        val output = stdout.toString().trim()
        val lines = output.lines()
        val httpCode = lines.lastOrNull()?.toIntOrNull() ?: -1
        val body = lines.dropLast(1).joinToString("\n")
        if (httpCode in listOf(200, 201, 202)) {
            logger.lifecycle("Successfully uploaded to Central Portal! ($httpCode)")
            if (body.isNotBlank()) {
                logger.lifecycle(body)
            }
            logger.lifecycle("Check status at: https://central.sonatype.com/publishing")
            logger.lifecycle("Artifacts will sync to Maven Central in 15-30 minutes")
        } else {
            val errOut = stderr.toString()
            error("Upload failed (HTTP $httpCode).\nResponse: $body\n$errOut")
        }
    }
}

// Note: Snapshots are published via Central Portal bundle upload (publishToCentralPortal task)
// The CentralSnapshot repository is no longer needed

// Task for publishing to OSSRH via Nexus plugin (alternative method)
tasks.register("publishLinkLabToSonatype") {
    group = "publishing"
    description = "Publishes to OSSRH via Nexus plugin (for snapshots)"
    dependsOn("publishReleasePublicationToSonatypeRepository")
    doLast {
        logger.lifecycle("Published to Sonatype repository")
    }
}

tasks.register("releaseSonatypeRepository") {
    dependsOn(rootProject.tasks.named("closeAndReleaseSonatypeStagingRepository"))
    doLast {
        logger.lifecycle("Closed and released Sonatype staging repository")
    }
}