plugins {
    id("com.android.library") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    alias(libs.plugins.compose.compiler) apply false
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory.get())
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://central.sonatype.com/repository/maven-releases/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            
            // Credentials should be provided via gradle.properties or environment variables
            username.set(System.getenv("OSSRH_USERNAME") ?: findProperty("ossrhUsername")?.toString() ?: "")
            password.set(System.getenv("OSSRH_PASSWORD") ?: findProperty("ossrhPassword")?.toString() ?: "")
        }
    }
}

// Convenience tasks for publishing
tasks.register("publishToMavenCentral") {
    dependsOn(":linklab:publishLinkLabToSonatype")
    doLast {
        println("Publishing to Maven Central...")
    }
}

tasks.register("publishAndRelease") {
    dependsOn(":linklab:publishLinkLabToSonatype", ":linklab:releaseSonatypeRepository")
    doLast {
        println("Publishing and releasing to Maven Central...")
    }
}