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
        private const val VERSION = "2.0.15"
    }

    private fun apiConnection(method: String, path: String, authorized: Boolean = true): HttpURLConnection {
        val suffix = if (path.isBlank()) "" else "/" + path
        val conn = URL(API + "/repos/" + REPO + suffix).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (authorized && token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("User-Agent", "G-Codus-Admin/" + VERSION)
        return conn
    }

    private fun body(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    fun testToken() {
        if (token.isBlank()) throw IllegalStateException("Пустой GitHub token")

        // Реальная проверка токена: раньше здесь проверялась только непустая строка,
        // поэтому любое значение считалось успешным входом.
        val conn = apiConnection("GET", "", authorized = true)
        val code = conn.responseCode
        val response = body(conn)

        if (code !in 200..299) {
            val reason = shortError(response)
            throw IllegalStateException(
                when (code) {
                    401 -> "GitHub отклонил токен (HTTP 401): токен недействителен или отозван"
                    403 -> "GitHub запретил доступ (HTTP 403): проверь права Fine-grained token для репозитория G-Codus"
                    404 -> "GitHub не видит репозиторий G-Codus этим токеном (HTTP 404): проверь Repository access"
                    else -> "Проверка GitHub не пройдена (HTTP " + code + "): " + reason
                }
            )
        }
    }

    fun getFile(path: String): GitFile {
        val api = apiConnection("GET", "contents/" + path + "?ref=main", authorized = true)
        val code = api.responseCode
        val text = body(api)

        if (code in 200..299) {
            val json = JSONObject(text)
            if (json.optString("type") != "file") {
                throw IllegalStateException("GitHub вернул не файл: " + path)
            }

            val encoded = json.optString("content").replace("\\s".toRegex(), "")
            if (encoded.isNotBlank()) {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                if (bytes.isNotEmpty()) return GitFile(bytes, json.optString("sha").ifBlank { null })
            }
        }

        val rawUrl = RAW + "/" + REPO + "/main/" + path
        val raw = URL(rawUrl).openConnection() as HttpURLConnection
        raw.requestMethod = "GET"
        raw.instanceFollowRedirects = true
        raw.connectTimeout = 20_000
        raw.readTimeout = 30_000
        raw.setRequestProperty("User-Agent", "G-Codus-Admin/" + VERSION)
        val rawCode = raw.responseCode

        if (rawCode in 200..299) {
            val bytes = raw.inputStream.use { it.readBytes() }
            if (bytes.isNotEmpty()) return GitFile(bytes, null)
        }

        throw IllegalStateException(
            "Не удалось прочитать файл " + path +
                ". GitHub API HTTP " + code + " — " + shortError(text) +
                "; RAW HTTP " + rawCode
        )
    }

    fun getFileSha(path: String): String? {
        val conn = apiConnection("GET", "contents/" + path + "?ref=main", authorized = true)
        val text = body(conn)
        if (conn.responseCode == 404) return null
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удалось получить SHA файла " + path +
                    ": GitHub HTTP " + conn.responseCode + " — " + shortError(text)
            )
        }
        return JSONObject(text).optString("sha").ifBlank { null }
    }

    fun deleteFile(path: String, sha: String, message: String): String {
        if (sha.isBlank()) throw IllegalArgumentException("Не указан SHA для удаления " + path)

        val conn = apiConnection("DELETE", "contents/" + path, authorized = true)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val payload = JSONObject()
            .put("message", message)
            .put("sha", sha)
            .put("branch", "main")

        conn.outputStream.use {
            it.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val text = body(conn)
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удалось удалить " + path +
                    ": GitHub HTTP " + conn.responseCode + " — " + shortError(text)
            )
        }

        return JSONObject(text).optJSONObject("commit")?.optString("sha").orEmpty()
    }

    fun triggerDataSync(reason: String) {
        val conn = apiConnection("POST", "dispatches", authorized = true)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val payload = JSONObject()
            .put("event_type", "gcodus-data-sync")
            .put("client_payload", JSONObject().put("reason", reason))
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val text = body(conn)
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException(
                "Не удалось запустить синхронизацию данных: GitHub HTTP " + conn.responseCode + " — " + shortError(text)
            )
        }
    }

    fun putFile(path: String, bytes: ByteArray, sha: String?, message: String): String {
        val conn = apiConnection("PUT", "contents/" + path, authorized = true)
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
                "Не удалось сохранить " + path +
                    ": GitHub HTTP " + conn.responseCode + " — " + shortError(text)
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
