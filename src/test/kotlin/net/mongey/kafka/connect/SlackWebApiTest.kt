package net.mongey.kafka.connect

import com.sun.net.httpserver.HttpServer
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.errors.RetriableException
import org.apache.kafka.connect.sink.SinkRecord
import org.apache.kafka.connect.sink.SinkTaskContext
import org.junit.Assert.*
import org.junit.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class SlackWebApiTest {
    @Test
    fun `task preserves public no argument constructor`() {
        assertNotNull(SlackSinkTask::class.java.getConstructor().newInstance())
    }

    @Test
    fun `real HTTP channel sends escape text and cache name resolution`() = SlackHttp().use { slack ->
        val task = slack.task(channel = "#orders")
        val text = "Quote \" backslash \\ newline\nDublin ☘️ 東京"
        task.put(listOf(record(text, 0), record(null, 1), record(emptyMap<String, Any>(), 2), record("next", 3)))
        assertEquals(listOf("conversations.list", "chat.postMessage", "chat.postMessage"), slack.requests.map { it.method })
        val lookup = slack.requests.first()
        assertEquals("GET", lookup.verb)
        assertEquals("types=public_channel%2Cprivate_channel&exclude_archived=true&limit=200", lookup.query)
        assertTrue(slack.requests.all { it.authorization == "Bearer synthetic-test-token" })
        val post = slack.requests[1]
        assertEquals("POST", post.verb)
        assertEquals("application/json; charset=utf-8", post.contentType)
        assertEquals(mapOf("channel" to "CORDERS", "text" to "Hello $text"), post.json())
        assertEquals("Hello next", slack.requests.last().json()["text"])
    }

    @Test
    fun `direct channel group and DM IDs skip lookups`() {
        for (id in listOf("C123", "G123", "D123")) SlackHttp().use { slack ->
            slack.task(channel = id).put(listOf(record("one")))
            assertEquals(listOf("chat.postMessage"), slack.requests.map { it.method })
            assertEquals(id, slack.requests.single().json()["channel"])
        }
    }

    @Test
    fun `username opens DM and caches both resolutions`() = SlackHttp().use { slack ->
        slack.task(channel = null, username = "@goku").put(listOf(record("one", 0), record("two", 1)))
        assertEquals(listOf("users.list", "conversations.open", "chat.postMessage", "chat.postMessage"), slack.requests.map { it.method })
        assertEquals("limit=200", slack.requests.first().query)
        assertEquals(mapOf("users" to "UGOKU"), slack.requests[1].json())
        assertEquals("DUSER", slack.requests[2].json()["channel"])
    }

    @Test
    fun `user and enterprise user IDs skip user lookup`() {
        for (id in listOf("U123", "W123")) SlackHttp().use { slack ->
            slack.task(channel = null, username = id).put(listOf(record("one")))
            assertEquals(listOf("conversations.open", "chat.postMessage"), slack.requests.map { it.method })
            assertEquals(id, slack.requests.first().json()["users"])
        }
    }

    @Test
    fun `both destinations receive each record and blank destination is ignored`() = SlackHttp().use { slack ->
        slack.task(username = "goku").put(listOf(record("one")))
        assertEquals(listOf("CORDERS", "DUSER"), slack.requests.filter { it.method == "chat.postMessage" }.map { it.json()["channel"] })
        slack.requests.clear()
        slack.task(channel = " ", username = "U123").put(listOf(record("two")))
        assertEquals(listOf("conversations.open", "chat.postMessage"), slack.requests.map { it.method })
    }

    @Test
    fun `channel and user pagination follows encoded cursors even after empty pages`() {
        for ((field, channel, user, found) in listOf(
            listOf("channels", "orders", null, "{\"id\":\"CORDERS\",\"name\":\"orders\"}"),
            listOf("members", null, "goku", "{\"id\":\"UGOKU\",\"name\":\"goku\"}")
        )) SlackHttp().use { slack ->
            var page = 0
            slack.respond = { request ->
                if (request.method.endsWith(".list")) {
                    page++
                    Reply(body = if (page == 1) """{"ok":true,"$field":[],"response_metadata":{"next_cursor":"next +/= cursor"}}"""
                        else """{"ok":true,"$field":[$found],"response_metadata":{"next_cursor":""}}""")
                } else slack.success(request)
            }
            slack.task(channel, user).put(listOf(record("one")))
            assertEquals(2, page)
            assertTrue(slack.requests[1].query!!.endsWith("cursor=next+%2B%2F%3D+cursor"))
        }
    }

    @Test
    fun `startup empty batches tombstones and stop never need Slack`(): Unit = SlackHttp().use { slack ->
        val task = SlackSinkTask(slack.base)
        task.put(emptyList())
        task.start(properties())
        task.put(emptyList())
        task.put(listOf(record(null, 0), record(emptyMap<String, Any>(), 1)))
        task.stop()
        task.stop()
        assertTrue(slack.requests.isEmpty())
        assertThrows(ConnectException::class.java) { task.put(listOf(record("one"))) }
        assertThrows(ConnectException::class.java) { SlackSinkTask().put(listOf(record("one"))) }
    }

    @Test
    fun `restart and separate tasks do not share destination caches`() = SlackHttp().use { slack ->
        val task = slack.task()
        task.put(listOf(record("one")))
        task.stop()
        task.start(properties())
        task.put(listOf(record("two")))
        slack.task().put(listOf(record("three")))
        assertEquals(3, slack.requests.count { it.method == "conversations.list" })
    }

    @Test
    fun `HTTP rate limits request worker delay without retrying inside put`() {
        for ((header, expected) in listOf("2" to 2000L, null to 60000L, "broken" to 60000L,
            "-1" to 60000L, "999999999999999999999" to 60000L, "999999" to 999999000L)) SlackHttp().use { slack ->
            slack.respond = { Reply(429, "do not log synthetic-test-token", header) }
            val task = slack.task(channel = "C123")
            val delays = mutableListOf<Long>()
            task.initialize(Proxy.newProxyInstance(SinkTaskContext::class.java.classLoader, arrayOf(SinkTaskContext::class.java)) { _, method, args ->
                if (method.name == "timeout") delays += args!![0] as Long
                null
            } as SinkTaskContext)
            val failure = assertThrows(RetriableException::class.java) { task.put(listOf(record("one"))) }
            assertEquals(listOf(expected), delays)
            assertEquals(1, slack.requests.size)
            assertFalse(failure.toString().contains("synthetic-test-token"))
        }
    }

    @Test
    fun `server errors are retryable and lookup is attempted again`() = SlackHttp().use { slack ->
        var attempts = 0
        slack.respond = { request -> if (attempts++ == 0) Reply(503, "secret body") else slack.success(request) }
        val task = slack.task()
        val failure = assertThrows(RetriableException::class.java) { task.put(listOf(record("one"))) }
        assertNull(failure.cause)
        task.put(listOf(record("one")))
        assertEquals(listOf("conversations.list", "conversations.list", "chat.postMessage"), slack.requests.map { it.method })
    }

    @Test
    fun `transport failure is retryable with safe diagnostics`() {
        val slack = SlackHttp()
        val task = slack.task(channel = "C123")
        slack.close()
        val failure = assertThrows(RetriableException::class.java) { task.put(listOf(record("one"))) }
        assertNull(failure.cause)
        assertFalse(failure.toString().contains("synthetic-test-token"))
    }

    @Test
    fun `cancellation preserves interrupt and is not retryable`() = SlackHttp().use { slack ->
        val task = slack.task(channel = "C123")
        try {
            Thread.currentThread().interrupt()
            val failure = assertThrows(ConnectException::class.java) { task.put(listOf(record("one"))) }
            assertFalse(failure is RetriableException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `HTTP client refuses redirects that could disclose credentials`() = SlackHttp().use { slack ->
        slack.respond = { Reply(302, "", location = slack.base.resolve("leaked").toString()) }
        assertThrows(ConnectException::class.java) { slack.task(channel = "C123").put(listOf(record("one"))) }
        assertEquals(1, slack.requests.size)
    }

    @Test
    fun `permanent HTTP errors and API errors fail without logging body`() {
        for (reply in listOf(Reply(401, "synthetic-test-token"), Reply(body = """{"ok":false,"error":"invalid_auth"}"""),
            Reply(body = """{"ok":false,"error":"synthetic-test-token\nprivate text"}"""))) SlackHttp().use { slack ->
            slack.respond = { reply }
            val failure = assertThrows(ConnectException::class.java) { slack.task(channel = "C123").put(listOf(record("one"))) }
            assertFalse(failure is RetriableException)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("synthetic-test-token"))
            if (reply.body.contains("invalid_auth")) assertTrue(failure.message!!.contains("invalid_auth"))
        }
    }

    @Test
    fun `malformed message replies cannot confirm delivery or leak body`() {
        for (body in listOf("", "not JSON synthetic-test-token", "[]", "null", "{}", "{\"ok\":\"true\"}",
            "{\"ok\":true}", "{\"ok\":true,\"channel\":7,\"ts\":\"1.0\"}",
            "{\"ok\":true,\"channel\":\"COTHER\",\"ts\":\"1.0\"}",
            "{\"ok\":true,\"channel\":\"C123\",\"ts\":1.0}",
            "{\"ok\":true,\"channel\":\"C123\",\"ts\":\"\"}",
            "{\"ok\":true,\"channel\":\"C123\",\"ts\":\"1.0\"} {}")) SlackHttp().use { slack ->
            slack.respond = { Reply(body = body) }
            val failure = assertThrows(ConnectException::class.java) { slack.task(channel = "C123").put(listOf(record("one"))) }
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("synthetic-test-token"))
        }
    }

    @Test
    fun `unknown or malformed destinations fail before posting`() {
        for ((props, body) in listOf(
            properties() to """{"ok":true,"channels":[]}""",
            properties(username = "goku", channel = null) to """{"ok":true,"members":[]}""",
            properties() to """{"ok":true,"channels":{}}""",
            properties() to """{"ok":true,"channels":[{"name":"orders","id":null}]}""",
            properties() to """{"ok":true,"channels":[{"name":5,"id":"CORDERS"}]}""",
            properties() to """{"ok":true,"channels":[],"response_metadata":{"next_cursor":2}}""",
            properties(channel = null, username = "U123") to """{"ok":true,"channel":{"id":"CWRONG"}}"""
        )) SlackHttp().use { slack ->
            slack.respond = { Reply(body = body) }
            val task = SlackSinkTask(slack.base).apply { start(props) }
            assertThrows(ConnectException::class.java) { task.put(listOf(record("one"))) }
            assertFalse(slack.requests.any { it.method == "chat.postMessage" })
        }
    }

    @Test
    fun `repeated pagination cursors fail instead of looping`() = SlackHttp().use { slack ->
        slack.respond = { Reply(body = """{"ok":true,"channels":[],"response_metadata":{"next_cursor":"same"}}""") }
        assertThrows(ConnectException::class.java) { slack.task().put(listOf(record("one"))) }
        assertEquals(2, slack.requests.size)
    }

    @Test
    fun `failed DM open is retried and not cached`() = SlackHttp().use { slack ->
        var opens = 0
        slack.respond = { request ->
            if (request.method == "conversations.open" && opens++ == 0) Reply(502) else slack.success(request)
        }
        val task = slack.task(channel = null, username = "goku")
        assertThrows(RetriableException::class.java) { task.put(listOf(record("one"))) }
        task.put(listOf(record("one")))
        assertEquals(listOf("users.list", "conversations.open", "conversations.open", "chat.postMessage"), slack.requests.map { it.method })
    }

    @Test
    fun `identical payloads at distinct Kafka offsets both deliver`() = SlackHttp().use { slack ->
        slack.task(channel = "C123").put(listOf(record("same", 0), record("same", 1)))
        assertEquals(listOf("Hello same", "Hello same"), slack.requests.map { it.json()["text"] })
    }

    @Test
    fun `retry resumes at failed delivery without starving later records`() = SlackHttp().use { slack ->
        var posts = 0
        slack.respond = { request -> if (request.method == "chat.postMessage" && ++posts == 2) Reply(429, retryAfter = "1") else slack.success(request) }
        val task = slack.task(channel = "C123")
        val batch = listOf(record("one", 0), record("two", 1), record("three", 2))
        assertThrows(RetriableException::class.java) { task.put(batch) }
        task.put(batch)
        assertEquals(listOf("Hello one", "Hello two", "Hello two", "Hello three"), slack.requests.map { it.json()["text"] })
        task.put(batch)
        assertEquals(7, slack.requests.size)
    }

    @Test
    fun `retry resumes at DM after successful channel delivery`() = SlackHttp().use { slack ->
        var dmPosts = 0
        slack.respond = { request ->
            if (request.method == "chat.postMessage" && request.json()["channel"] == "DUSER" && dmPosts++ == 0) Reply(429)
            else slack.success(request)
        }
        val task = slack.task(channel = "C123", username = "U123")
        val batch = listOf(record("one"))
        assertThrows(RetriableException::class.java) { task.put(batch) }
        task.put(batch)
        assertEquals(listOf("C123", "DUSER", "DUSER"), slack.requests.filter { it.method == "chat.postMessage" }.map { it.json()["channel"] })
    }

    @Test
    fun `all destinations resolve before sending and deleted usernames are skipped`() = SlackHttp().use { slack ->
        slack.respond = { request ->
            if (request.method == "users.list") Reply(body = """{"ok":true,"members":[{"id":"UOLD","name":"goku","deleted":true}]}""")
            else slack.success(request)
        }
        assertThrows(ConnectException::class.java) { slack.task(username = "goku").put(listOf(record("one"))) }
        assertFalse(slack.requests.any { it.method == "chat.postMessage" })
    }

    @Test
    fun `revocation clears accepted progress using original Kafka coordinates after SMT`() = SlackHttp().use { slack ->
        var posts = 0
        slack.respond = { request -> if (request.method == "chat.postMessage" && ++posts == 2) Reply(503) else slack.success(request) }
        val task = slack.task(channel = "C123")
        val transformed = record("one").newRecord("transformed", 8, null, null, null, mapOf("name" to "one"), null)
        val batch = listOf(transformed, record("two", 1))
        assertThrows(RetriableException::class.java) { task.put(batch) }
        task.close(listOf(org.apache.kafka.common.TopicPartition("orders", 0)))
        task.put(batch)
        assertEquals(listOf("Hello one", "Hello two", "Hello one", "Hello two"), slack.requests.map { it.json()["text"] })
    }

    @Test
    fun `general retries request the worker default delay`(): Unit = SlackHttp().use { slack ->
        slack.respond = { Reply(503) }
        val task = slack.task(channel = "C123")
        val delays = mutableListOf<Long>()
        task.initialize(Proxy.newProxyInstance(SinkTaskContext::class.java.classLoader, arrayOf(SinkTaskContext::class.java)) { _, method, args ->
            if (method.name == "timeout") delays += args!![0] as Long
            null
        } as SinkTaskContext)
        assertThrows(RetriableException::class.java) { task.put(listOf(record("one"))) }
        assertEquals(listOf(1000L), delays)
    }

    @Test
    fun `known temporary API errors are retryable`() {
        for (code in listOf("internal_error", "fatal_error", "service_unavailable", "request_timeout", "ratelimited")) SlackHttp().use { slack ->
            slack.respond = { Reply(body = """{"ok":false,"error":"$code"}""") }
            assertThrows(RetriableException::class.java) { slack.task(channel = "C123").put(listOf(record("one"))) }
        }
    }

}

internal class SlackHttp : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val base: URI = URI.create("http://127.0.0.1:${server.address.port}/api/")
    val requests = CopyOnWriteArrayList<Request>()
    var respond: (Request) -> Reply = ::success

    init {
        server.createContext("/api/") { exchange ->
            val request = Request(exchange.requestURI.path.removePrefix("/api/"), exchange.requestMethod,
                exchange.requestURI.rawQuery, exchange.requestHeaders.getFirst("Authorization"),
                exchange.requestHeaders.getFirst("Content-Type"), exchange.requestBody.readAllBytes().toString(Charsets.UTF_8))
            requests += request
            val reply = respond(request)
            reply.retryAfter?.let { exchange.responseHeaders.set("Retry-After", it) }
            reply.location?.let { exchange.responseHeaders.set("Location", it) }
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    fun task(channel: String? = "orders", username: String? = null) = SlackSinkTask(base).apply { start(properties(channel, username)) }

    fun success(request: Request) = Reply(body = when (request.method) {
        "conversations.list" -> """{"ok":true,"channels":[{"id":"CORDERS","name":"orders"}]}"""
        "users.list" -> """{"ok":true,"members":[{"id":"UGOKU","name":"goku"}]}"""
        "conversations.open" -> """{"ok":true,"channel":{"id":"DUSER"}}"""
        "chat.postMessage" -> """{"ok":true,"channel":"${request.json()["channel"]}","ts":"123.456"}"""
        else -> """{"ok":false,"error":"unknown_method"}"""
    })

    override fun close() = server.stop(0)
}

internal data class Reply(val status: Int = 200, val body: String = "", val retryAfter: String? = null, val location: String? = null)
internal data class Request(val method: String, val verb: String, val query: String?, val authorization: String?, val contentType: String?, val body: String) {
    fun json(): Map<String, String> = JsonMapper().readTree(body).properties().associate { it.key to it.value.stringValue() }
}

private fun record(value: Any?, offset: Long = 0): SinkRecord = SinkRecord("orders", 0, null, null, null,
    if (value is String) mapOf("name" to value) else value, offset)

private fun properties(channel: String? = "orders", username: String? = null): Map<String, String> = buildMap {
    put("slack.token", "synthetic-test-token")
    put("message.template", "Hello \${name}")
    channel?.let { put("slack.channel", it) }
    username?.let { put("slack.username", it) }
}
