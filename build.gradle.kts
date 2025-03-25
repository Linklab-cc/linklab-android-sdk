plugins {
    id("com.android.library") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    alias(libs.plugins.compose.compiler) apply false
    id("io.github.gradle-nexus.publish-plugin") version "1.3.0"
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

nexusPublishing {
    repositories {
        sonatype {
            // For library hosted on Maven Central (via Sonatype OSSRH)
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            
            // Credentials should be provided via gradle.properties or environment variables
            username.set(System.getenv("OSSRH_USERNAME") ?: findProperty("ossrhUsername")?.toString() ?: "")
            password.set(System.getenv("OSSRH_PASSWORD") ?: findProperty("ossrhPassword")?.toString() ?: "")
        }
    }
}