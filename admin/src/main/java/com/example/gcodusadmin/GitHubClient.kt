package com.example.gcodusadmin

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class GitFile(val bytes: ByteArray, val sha: String)

class GitHubClient(private val token: String) {
    companion object {
        const val REPO = "sweeety601/G-Codus"
        private const val API = "https://api.github.com"
    }

    private fun connection(method: String, path: String): HttpURLConnection {
        val conn = URL(API + "/repos/" + REPO + "/" + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("User-Agent", "G-Codus-Admin")
        return conn
    }

    private fun body(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    fun testToken() {
        val userConn = URL(API + "/user").openConnection() as HttpURLConnection
        userConn.requestMethod = "GET"
        userConn.connectTimeout = 15_000
        userConn.readTimeout = 15_000
        userConn.setRequestProperty("Accept", "application/vnd.github+json")
        userConn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        userConn.setRequestProperty("Authorization", "Bearer " + token)
        userConn.setRequestProperty("User-Agent", "G-Codus-Admin")
        val userText = body(userConn)
        if (userConn.responseCode !in 200..299) {
            throw IllegalStateException(
                "GitHub token invalid: HTTP " + userConn.responseCode + " " + shortError(userText)
            )
        }

        val repoConn = URL(API + "/repos/" + REPO).openConnection() as HttpURLConnection
        repoConn.requestMethod = "GET"
        repoConn.connectTimeout = 15_000
        repoConn.readTimeout = 15_000
        repoConn.setRequestProperty("Accept", "application/vnd.github+json")
        repoConn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        repoConn.setRequestProperty("Authorization", "Bearer " + token)
        repoConn.setRequestProperty("User-Agent", "G-Codus-Admin")
        val repoText = body(repoConn)
        if (repoConn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Нет доступа к репозиторию " + REPO +
                    ": HTTP " + repoConn.responseCode + " " + shortError(repoText) +
                    ". Для Fine-grained token выбери Repository access → Only select repositories → " + REPO +
                    ", а Repository permissions → Contents = Read and write."
            )
        }

        val fileConn = URL(API + "/repos/" + REPO + "/contents/library/seed/01_Wuthering_Waves.xlsx?ref=main")
            .openConnection() as HttpURLConnection
        fileConn.requestMethod = "GET"
        fileConn.connectTimeout = 15_000
        fileConn.readTimeout = 30_000
        fileConn.setRequestProperty("Accept", "application/vnd.github+json")
        fileConn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        fileConn.setRequestProperty("Authorization", "Bearer " + token)
        fileConn.setRequestProperty("User-Agent", "G-Codus-Admin")
        val fileText = body(fileConn)
        if (fileConn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Нет доступа к файлу library/seed/01_Wuthering_Waves.xlsx: HTTP " +
                    fileConn.responseCode + " " + shortError(fileText)
            )
        }
    }

    fun getFile(path: String): GitFile {
        val conn = connection("GET", "contents/" + path + "?ref=main")
        val text = body(conn)
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "GET " + path + ": HTTP " + conn.responseCode + " " + shortError(text)
            )
        }
        val json = JSONObject(text)
        val content = json.getString("content").replace("\n", "").replace("\r", "")
        return GitFile(Base64.decode(content, Base64.DEFAULT), json.getString("sha"))
    }

    fun putFile(path: String, bytes: ByteArray, sha: String?, message: String): String {
        val conn = connection("PUT", "contents/" + path)
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
                "PUT " + path + ": HTTP " + conn.responseCode + " " + shortError(text)
            )
        }
        return JSONObject(text).optJSONObject("commit")?.optString("sha").orEmpty()
    }

    private fun shortError(text: String): String {
        return try {
            val json = JSONObject(text)
            val message = json.optString("message").trim()
            val documentation = json.optString("documentation_url").trim()
            if (message.isNotBlank()) {
                if (documentation.isNotBlank()) message + " (" + documentation + ")" else message
            } else {
                text.replace("\n", " ").replace("\r", " ").trim().take(300)
            }
        } catch (_: Exception) {
            text.replace("\n", " ").replace("\r", " ").trim().take(300)
        }
    }
}
