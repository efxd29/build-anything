pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Scrcpy"
include(":app")

// ---------------------------------------------------------------------------
// CI failure reporter (temporary diagnostic).
//
// The GitHub Actions step log for this repository is not readable from
// outside the workflow, so on failure this hook re-prints the failure
// (exception chain + stack trace) as GitHub workflow commands, which
// surface as annotations on the public run page. Registered from settings
// so it is in place no matter which later phase fails. It never changes
// the build outcome.
// ---------------------------------------------------------------------------
gradle.buildFinished {
    val failure = this.failure ?: return@buildFinished
    try {
        val trace = java.io.StringWriter().also { w ->
            failure.printStackTrace(java.io.PrintWriter(w))
        }.toString()
        fun esc(s: String): String = s
            .replace("%", "%25")
            .replace("\r", "%0D")
            .replace("\n", "%0A")
        trace.chunked(3000).take(10).forEachIndexed { i, chunk ->
            println("::error title=BuildFailure-${i + 1}::${esc(chunk)}")
        }
    } catch (t: Throwable) {
        println("CI failure reporter failed: ${t.message}")
    }
}
