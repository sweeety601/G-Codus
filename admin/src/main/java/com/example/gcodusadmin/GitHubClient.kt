package com.example.gcodusadmin

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitFile(val bytes: ByteArray, val sha: String?)

class GitHubClient(private val token: String) {
    companion object {
        const val REPO = "sweeety601/G-Codus"
        private const val API = "https://api.github.com"
        private const val RAW = "https://raw.githubusercontent.com/sweeety601/G-Codus/main"
        private const val TEST_FILE = "library/seed/01_Wuthering_Waves.xlsx"
    }

    private fun apiConnection(method: String, path: String): HttpURLConnection {
        val safePath = path.split("/").joinToString("/") { part ->
            URLEncoder.encode(part, "UTF-8").replace("+", "%20")
        }
        val conn = URL(API + "/repos/" + REPO + "/" + safePath).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("User-Agent", "G-Codus-Admin/2.0")
        return conn
    }

    private fun rawConnection(path: String): HttpURLConnection {
        val encoded = path.split("/").joinToString("/") { part ->
            URLEncoder.encode(part, "UTF-8").replace("+", "%20")
        }
        val conn = URL(RAW + "/" + encoded).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "G-Codus-Admin/2.0")
        return conn
    }

    private fun body(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    fun testToken() {
        val raw = rawConnection(TEST_FILE)
        val rawText = body(raw)
        if (raw.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удаётся прочитать публичный файл репозитория: HTTP " + raw.responseCode +
                    " " + shortError(rawText)
            )
        }

        val api = apiConnection("GET", "contents/" + TEST_FILE + "?ref=main")
        val apiText = body(api)
        if (api.responseCode !in 200..299) {
            throw IllegalStateException(
                "GitHub видит репозиторий, но токен не имеет доступа через Contents API: HTTP " +
                    api.responseCode + " " + shortError(apiText) +
                    ". Для Fine-grained token: G-Codus → Contents → Read and write."
            )
        }
    }

    fun getFile(path: String): GitFile {
        val raw = rawConnection(path)
        val rawBytes = if (raw.responseCode in 200..299) {
            val stream = raw.inputStream
            stream.use { it.readBytes() }
        } else null

        if (rawBytes != null) return GitFile(rawBytes, null)

        val conn = apiConnection("GET", "contents/" + path + "?ref=main")
        val text = body(conn)
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удалось получить " + path + ": HTTP " + conn.responseCode + " " + shortError(text)
            )
        }
        val json = JSONObject(text)
        val content = json.getString("content").replace("\n", "").replace("\r", "")
        return GitFile(Base64.decode(content, Base64.DEFAULT), json.getString("sha"))
    }

    fun getFileSha(path: String): String? {
        val conn = apiConnection("GET", "contents/" + path + "?ref=main")
        val text = body(conn)
        if (conn.responseCode == 404) return null
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удалось получить SHA файла " + path + ": HTTP " + conn.responseCode + " " + shortError(text)
            )
        }
        return JSONObject(text).getString("sha")
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
                "Не удалось сохранить " + path + ": HTTP " + conn.responseCode + " " + shortError(text)
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
