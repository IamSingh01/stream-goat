// The Android app: a separate Gradle build so the Linux build and its CI stay untouched.
// The CloudStream library is compiled from the same pinned upstream checkout as the Linux app
// (run ../scripts/fetch-upstream.sh first).
pluginManagement {
    repositories {
        google()
        // Google's copy of Maven Central first: Maven Central often refuses GitHub's build machines ("Too Many
        // Requests"), which failed the APK builds. Same files, other servers; Maven Central stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        // Google's copy of Maven Central first: Maven Central often refuses GitHub's build machines ("Too Many
        // Requests"), which failed the APK builds. Same files, other servers; Maven Central stays as the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // NewPipe Extractor (YouTube), as in the official app.
        maven("https://jitpack.io")
    }
}

rootProject.name = "cloudstream-android"
include(":cs-library", ":app")
