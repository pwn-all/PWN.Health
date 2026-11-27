// No version catalog: one module, so the versions live in app/build.gradle.kts.
// Dependabot raises them (.github/dependabot.yml), and CI writes the checksums each new version needs
// into gradle/verification-metadata.xml on its branch (.github/workflows/dependabot-gradle.yml).
plugins {
    // AGP 9 has Kotlin support built in — no separate kotlin-android plugin.
    id("com.android.application") version "9.5.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.14" apply false
}
