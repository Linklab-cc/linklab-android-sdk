plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.nexus.publish)
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory.get())
}

nexusPublishing {
    repositories {
        sonatype {
            // Legacy Sonatype OSSRH endpoints (only used by the legacy publish tasks below)
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))

            // Credentials come from -P flags, ~/.gradle/gradle.properties or environment variables.
            // They are never checked into this repository.
            username.set(System.getenv("OSSRH_USERNAME") ?: findProperty("ossrhUsername")?.toString() ?: "")
            password.set(System.getenv("OSSRH_PASSWORD") ?: findProperty("ossrhPassword")?.toString() ?: "")
        }
    }
}

// Convenience tasks for publishing

// For snapshots (via Central Portal bundle upload)
tasks.register("publishSnapshot") {
    group = "publishing"
    description = "Build, sign, and publish SNAPSHOT to Maven Central via Central Portal"
    dependsOn("clean", ":linklab:publishToCentralPortal")
    doFirst {
        val v = findProperty("version")?.toString() ?: ""
        if (!v.endsWith("-SNAPSHOT")) {
            throw GradleException("publishSnapshot is for SNAPSHOT versions only. Current version: $v")
        }
    }
    doLast {
        val version = findProperty("version")?.toString() ?: "unknown"
        println("Snapshot cc.linklab:android:$version uploaded to Central Portal")
        println("Check status at: https://central.sonatype.com/publishing")
    }
}

// New Central Portal (automated, for releases)
tasks.register("publishRelease") {
    group = "publishing"
    description = "Build, sign, and publish release to Maven Central (Central Portal)"
    dependsOn("clean", ":linklab:publishToCentralPortal")
    doFirst {
        val v = findProperty("version")?.toString() ?: ""
        if (v.endsWith("SNAPSHOT")) {
            throw GradleException("publishRelease is for non-SNAPSHOT versions. For snapshots use: ./gradlew publishSnapshot")
        }
    }
    doLast {
        println("Publishing complete!")
    }
}

// Legacy OSSRH tasks
tasks.register("publishToMavenCentral") {
    dependsOn(":linklab:publishLinkLabToSonatype")
}

tasks.register("publishAndRelease") {
    dependsOn(":linklab:publishLinkLabToSonatype", ":linklab:releaseSonatypeRepository")
}
