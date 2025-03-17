plugins {
    id("com.android.library") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    alias(libs.plugins.compose.compiler) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}