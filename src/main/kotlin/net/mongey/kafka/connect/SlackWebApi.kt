package net.mongey.kafka.connect

import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.errors.RetriableException
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration

internal class SlackWebApi(private val token: String, private val apiBase: URI) {
    fun channelId(destination: String): String {
        if (CHANNEL_ID.matches(destination)) return destination
        return findId("conversations.list", "channels", destination.removePrefix("#"), CHANNEL_ID,
            "types=public_channel%2Cprivate_channel&exclude_archived=true&limit=200")
    }

    fun userId(destination: String): String = if (USER_ID.matches(destination)) destination else
        findId("users.list", "members", destination.removePrefix("@"), USER_ID, "limit=200")

    fun openDirectMessage(userId: String): String {
        val response = request("conversations.open", mapOf("users" to userId))
        return requireId(response.path("channel").path("id"), DM_ID, "conversations.open")
    }

    fun postMessage(channel: String, text: String) {
        val response = request("chat.postMessage", mapOf("channel" to channel, "text" to text))
        if (requireId(response.path("channel"), CHANNEL_ID, "chat.postMessage") != channel ||
            !response.path("ts").isString || !TIMESTAMP.matches(response.path("ts").stringValue())) {
            throw malformed("chat.postMessage")
        }
    }

    private fun findId(method: String, collection: String, name: String, idPattern: Regex, query: String): String {
        var cursor = ""
        val cursors = mutableSetOf<String>()
        do {
            val response = request(method, query = query + if (cursor.isEmpty()) "" else "&cursor=${URLEncoder.encode(cursor, UTF_8)}")
            val entries = response.path(collection)
            if (!entries.isArray) throw malformed(method)
            for (entry in entries) {
                val deleted = entry.get("deleted")
                if (deleted != null && !deleted.isBoolean) throw malformed(method)
                if (method == "users.list" && deleted?.booleanValue() == true) continue
                if (!entry.path("name").isString) throw malformed(method)
                val id = requireId(entry.path("id"), idPattern, method)
                if (entry.path("name").stringValue() == name) return id
            }
            val metadata = response.get("response_metadata")
            cursor = if (metadata == null) "" else {
                if (!metadata.isObject) throw malformed(method)
                val next = metadata.get("next_cursor")
                if (next == null) "" else {
                    if (!next.isString) throw malformed(method)
                    next.stringValue().trim()
                }
            }
            if (cursor.isNotEmpty() && !cursors.add(cursor)) throw malformed(method)
        } while (cursor.isNotEmpty())
        throw ConnectException("Slack $method destination was not found")
    }

    private fun request(method: String, body: Map<String, String>? = null, query: String = ""): JsonNode {
        val request = HttpRequest.newBuilder(apiBase.resolve(method + if (query.isEmpty()) "" else "?$query"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .apply {
                if (body == null) GET() else {
                    header("Content-Type", "application/json; charset=utf-8")
                    POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), UTF_8))
                }
            }.build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString(UTF_8))
        } catch (_: IOException) {
            throw RetriableException("Slack $method transport failed")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ConnectException("Slack $method interrupted")
        }
        val status = response.statusCode()
        if (status == 429) {
            val seconds = response.headers().firstValue("Retry-After").orElse("").toLongOrNull()
                ?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 } ?: 60
            throw SlackRateLimitException(seconds * 1000, "Slack $method HTTP 429")
        }
        if (status in 500..599) throw RetriableException("Slack $method HTTP $status")
        if (status !in 200..299) throw ConnectException("Slack $method HTTP $status")
        val result = try {
            mapper.readTree(response.body())
        } catch (_: JacksonException) {
            throw malformed(method)
        }
        if (result == null || !result.isObject || !result.path("ok").isBoolean) throw malformed(method)
        if (!result.path("ok").booleanValue()) {
            val error = result.path("error")
            val code = if (error.isString && ERROR_CODE.matches(error.stringValue())) error.stringValue() else "unknown_error"
            if (code in TRANSIENT_ERRORS) throw RetriableException("Slack $method rejected request ($code)")
            throw ConnectException("Slack $method rejected request ($code)")
        }
        return result
    }

    private fun requireId(node: JsonNode, pattern: Regex, method: String): String {
        if (!node.isString || !pattern.matches(node.stringValue())) throw malformed(method)
        return node.stringValue()
    }

    private fun malformed(method: String) = ConnectException("Slack $method returned a malformed response")

    companion object {
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build()
        private val mapper = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
        private val CHANNEL_ID = Regex("[CGD][A-Z0-9]+")
        private val DM_ID = Regex("D[A-Z0-9]+")
        private val USER_ID = Regex("[UW][A-Z0-9]+")
        private val TIMESTAMP = Regex("[0-9]+\\.[0-9]+")
        private val TRANSIENT_ERRORS = setOf("internal_error", "fatal_error", "service_unavailable", "request_timeout", "ratelimited")
        private val ERROR_CODE = Regex("[a-z_]{1,64}")
    }
}

internal class SlackRateLimitException(val delayMs: Long, message: String) : RetriableException(message)
