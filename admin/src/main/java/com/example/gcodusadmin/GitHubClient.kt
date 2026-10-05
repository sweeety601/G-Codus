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
        private const val RAW = "https://raw.githubusercontent.com"
    }

    private fun apiConnection(method: String, path: String, authorized: Boolean = true): HttpURLConnection {
        val conn = URL(API + "/repos/" + REPO + "/" + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (authorized && token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("User-Agent", "G-Codus-Admin/2.0.7")
        return conn
    }

    private fun rawConnection(path: String): HttpURLConnection {
        val conn = URL(RAW + "/" + REPO + "/main/" + path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "G-Codus-Admin/2.0.7")
        return conn
    }

    private fun body(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    fun testToken() {
        if (token.isBlank()) throw IllegalStateException("Пустой GitHub token")
    }

    fun getFile(path: String): GitFile {
        // The repository is public. Read binary XLSX files directly from raw GitHub.
        // This avoids the Contents API's JSON/base64 handling for binary files entirely.
        val conn = rawConnection(path)
        val code = conn.responseCode
        if (code !in 200..299) {
            val text = body(conn)
            throw IllegalStateException("Не удалось прочитать " + path + ": GitHub RAW HTTP " + code + " — " + shortError(text))
        }
        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) throw IllegalStateException("GitHub вернул пустой файл " + path)
        // Reading must never require Contents API authentication.
        // SHA is fetched separately only by write operations.
        return GitFile(bytes, null)
    }

    fun getFileSha(path: String): String? {
        val conn = apiConnection("GET", "contents/" + path + "?ref=main", authorized = true)
        val text = body(conn)
        if (conn.responseCode == 404) return null
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Не удалось получить SHA файла " + path + ": GitHub HTTP " + conn.responseCode + " — " + shortError(text))
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
            throw IllegalStateException("Не удалось сохранить " + path + ": GitHub HTTP " + conn.responseCode + " — " + shortError(text))
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
