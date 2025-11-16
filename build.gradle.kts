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
            // Sonatype OSSRH endpoints
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            
            // Credentials from gradle.properties or environment variables
            username.set(System.getenv("OSSRH_USERNAME") ?: findProperty("ossrhUsername")?.toString() ?: "")
            password.set(System.getenv("OSSRH_PASSWORD") ?: findProperty("ossrhPassword")?.toString() ?: "")
        }
    }
}

// Convenience tasks for publishing

// New Central Portal (automated)
tasks.register("publishRelease") {
    group = "publishing"
    description = "Build, sign, and publish release to Maven Central (New Portal)"
    dependsOn("clean", ":linklab:publishToCentralPortal")
    doLast {
        println("✅ Publishing complete!")
    }
}

// Legacy OSSRH tasks
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