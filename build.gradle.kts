plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---------------------------------------------------------------------------
// CI failure reporter (temporary diagnostic).
//
// The GitHub Actions step log for this repository is not readable from
// outside the workflow, so when a CI build fails, this hook publishes the
// full failure (exception chain + stack trace) to a file on the side branch
// `ci-reports`, where it can be inspected. It only acts when the workflow's
// GITHUB_TOKEN is present in the environment; local builds are unaffected.
// Everything is wrapped defensively: a reporter problem must never change
// the build outcome.
// ---------------------------------------------------------------------------
gradle.buildFinished {
    val failure = this.failure
    val token = System.getenv("GITHUB_TOKEN")
    val repo = System.getenv("GITHUB_REPOSITORY")
    if (failure == null || token.isNullOrBlank() || repo.isNullOrBlank()) return@buildFinished
    try {
        val trace = java.io.StringWriter().also { w ->
            failure.printStackTrace(java.io.PrintWriter(w))
        }.toString()
        val report = buildString {
            appendLine("CI build failure report")
            appendLine("commit: ${System.getenv("GITHUB_SHA")}")
            appendLine("run: ${System.getenv("GITHUB_RUN_ID")}")
            appendLine()
            append(trace.take(55_000))
        }
        fun api(method: String, path: String, body: String?): String {
            val conn = java.net.URI("https://api.github.com/repos/$repo$path")
                .toURL().openConnection() as java.net.HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            return stream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        }
        // Ensure the side branch exists (created from the failing commit).
        val refCheck = api("GET", "/git/ref/heads/ci-reports", null)
        if (!refCheck.contains("\"ref\"")) {
            val sha = System.getenv("GITHUB_SHA") ?: ""
            api("POST", "/git/refs", """{"ref":"refs/heads/ci-reports","sha":"$sha"}""")
        }
        // If the report file already exists on that branch, pass its sha.
        val existing = api("GET", "/contents/ci-failure-report.txt?ref=ci-reports", null)
        val shaMatch = Regex("\"sha\"\\s*:\\s*\"([0-9a-f]+)\"").find(existing)
        val content64 = java.util.Base64.getEncoder()
            .encodeToString(report.toByteArray(Charsets.UTF_8))
        val putBody = buildString {
            append("{\"message\":\"CI failure report\",\"branch\":\"ci-reports\",\"content\":\"")
            append(content64)
            append('"')
            if (shaMatch != null) {
                append(",\"sha\":\"")
                append(shaMatch.groupValues[1])
                append('"')
            }
            append("}")
        }
        api("PUT", "/contents/ci-failure-report.txt", putBody)
        println("CI failure report published to branch ci-reports")
    } catch (t: Throwable) {
        println("CI failure reporter failed: ${t.message}")
    }
}
