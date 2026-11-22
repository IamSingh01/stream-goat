pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Google's copy of Maven Central first: Maven Central often refuses GitHub's build machines (it failed the
        // build with "Could not find org.openjfx:javafx-media"). Same files, other servers; Maven Central is the fallback.
        maven("https://maven-central.storage-download.googleapis.com/maven2/") {
            content { excludeGroupByRegex("(?i)com\\.github\\.teamnewpipe.*") }
        }
        mavenCentral {
            content { excludeGroupByRegex("(?i)com\\.github\\.teamnewpipe.*") }
        }
        // NewPipe Extractor (YouTube), as in the official app: only JitPack has it.
        maven("https://jitpack.io")
    }
}

rootProject.name = "cloudstream-linux"
include(":cs-library", ":android-shim", ":app")
include(":examples:InternetArchiveProvider", ":tests:DemoProvider")
