pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

// The build's own tools bring Guava and Bouncy Castle releases with published advisories:
// - AGP and its Lint bring Guava 33.4.0-jre and Bouncy Castle 1.79;
// - the test platform brings Guava 32.0.1-jre;
// - Room's KSP processor brings Guava 33.2.1-jre.
// None of them ships in the APK, but they run wherever the app is built, CI included. Every classpath here, the
// build script's included, takes the first release without the advisory instead. A -jre request
// takes the first fixed -jre and no later one, so the app's own 33.8.0-android still wins where both meet.
fun olderThan(version: String, fixed: String): Boolean {
    val have = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val need = fixed.split('.').map { it.toInt() }
    return (0 until maxOf(have.size, need.size))
        .map { (have.getOrElse(it) { 0 }).compareTo(need.getOrElse(it) { 0 }) }
        .firstOrNull { it != 0 }?.let { it < 0 } ?: false
}

fun DependencyResolveDetails.raiseAdvised() {
    val version = requested.version ?: return
    when {
        requested.group == "com.google.guava" && requested.name == "guava" && olderThan(version, "33.7.2") ->
            useVersion(if (version.endsWith("-android")) "33.7.2-android" else "33.7.2-jre")

        requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on") && olderThan(version, "1.80.2") ->
            useVersion("1.86")
    }
}

gradle.beforeProject {
    buildscript.configurations.configureEach { resolutionStrategy.eachDependency { raiseAdvised() } }
    configurations.configureEach { resolutionStrategy.eachDependency { raiseAdvised() } }
}

rootProject.name = "PwnHealth"
include(":app")
