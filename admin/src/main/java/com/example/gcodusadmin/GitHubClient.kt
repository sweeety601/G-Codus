package com.example.gcodusadmin

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class GitFile(val bytes: ByteArray, val sha: String?)

class GitHubClient(private val token: String) {
    companion object {
        const val REPO = "sweeety601/G-Codus"
        private const val API = "https://api.github.com"
        private const val TEST_FILE = "library/seed/01_Wuthering_Waves.xlsx"
    }

    private fun apiConnection(method: String, path: String): HttpURLConnection {
        val conn = URL(API + "/repos/" + REPO + "/" + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("User-Agent", "G-Codus-Admin/2.0.3")
        return conn
    }

    private fun body(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    fun testToken() {
        // The repository is public. We only validate the token itself by requesting
        // the repository metadata through the API. File access is checked when loading data.
        val conn = apiConnection("GET", "")
        val text = body(conn)
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("GitHub: HTTP " + conn.responseCode + " " + shortError(text))
        }
    }

    fun getFile(path: String): GitFile {
        // IMPORTANT: do not use raw.githubusercontent.com here. The Android client
        // previously received a 404 from raw even though the file exists. The Contents
        // API returns the binary XLSX as base64 and its real blob SHA in one response.
        val conn = apiConnection("GET", "contents/" + path + "?ref=main")
        val text = body(conn)
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удалось получить " + path + ": GitHub HTTP " + conn.responseCode + " — " + shortError(text)
            )
        }
        val json = JSONObject(text)
        val encoded = json.optString("content").replace("\n", "").replace("\r", "")
        if (encoded.isBlank()) {
            throw IllegalStateException("GitHub не вернул содержимое файла " + path)
        }
        return GitFile(Base64.decode(encoded, Base64.DEFAULT), json.optString("sha").ifBlank { null })
    }

    fun getFileSha(path: String): String? {
        val conn = apiConnection("GET", "contents/" + path + "?ref=main")
        val text = body(conn)
        if (conn.responseCode == 404) {
            return null
        }
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удалось получить SHA файла " + path + ": GitHub HTTP " + conn.responseCode + " — " + shortError(text)
            )
        }
        return JSONObject(text).optString("sha").ifBlank { null }
    }

    fun putFile(path: String, bytes: ByteArray, sha: String?, message: String): String {
        val conn = apiConnection("PUT", "contents/" + path)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val payload = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", "main")
        if (sha != null) payload.put("sha", sha)
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val text = body(conn)
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удалось сохранить " + path + ": GitHub HTTP " + conn.responseCode + " — " + shortError(text)
            )
        }
        return JSONObject(text).optJSONObject("commit")?.optString("sha").orEmpty()
    }

    private fun shortError(text: String): String {
        return try {
            val json = JSONObject(text)
            val message = json.optString("message").trim()
            val documentation = json.optString("documentation_url").trim()
            when {
                message.isNotBlank() && documentation.isNotBlank() -> message + " (" + documentation + ")"
                message.isNotBlank() -> message
                else -> text.replace("\n", " ").replace("\r", " ").trim().take(300)
            }
        } catch (_: Exception) {
            text.replace("\n", " ").replace("\r", " ").trim().take(300)
        }
    }
}
