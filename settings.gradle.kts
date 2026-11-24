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
        fun esc(s: String): String = s
            .replace("%", "%25")
            .replace("\r", "%0D")
            .replace("\n", "%0A")
        // Compact cause-chain summary first: exception class + message only,
        // so the root cause is visible in the first annotations.
        val roots = mutableListOf<Throwable>()
        roots.add(failure)
        try {
            val m = failure.javaClass.methods.firstOrNull { it.name == "getFailures" }
            @Suppress("UNCHECKED_CAST")
            val subs = m?.invoke(failure) as? List<Throwable>
            if (subs != null) roots.addAll(subs)
        } catch (t: Throwable) { /* ignore */ }
        val summary = StringBuilder()
        roots.forEachIndexed { ri, root ->
            var cur: Throwable? = root
            var depth = 0
            while (cur != null && depth < 12) {
                val msg = (cur.message ?: "").replace("\n", " | ").take(900)
                summary.append("R$ri D$depth ${cur.javaClass.name}: $msg\n")
                cur = cur.cause
                depth++
            }
        }
        var used = 0
        summary.toString().chunked(2500).take(5).forEach { chunk ->
            used++
            println("::error title=BuildCause-$used::${esc(chunk)}")
        }
        val trace = java.io.StringWriter().also { w ->
            failure.printStackTrace(java.io.PrintWriter(w))
        }.toString()
        trace.chunked(3000).take(10 - used).forEachIndexed { i, chunk ->
            println("::error title=BuildFailure-${i + 1}::${esc(chunk)}")
        }
    } catch (t: Throwable) {
        println("CI failure reporter failed: ${t.message}")
    }
}
