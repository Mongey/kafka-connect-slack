package net.mongey.kafka.connect

import com.ullink.slack.simpleslackapi.SlackChannel
import com.ullink.slack.simpleslackapi.SlackMessageHandle
import com.ullink.slack.simpleslackapi.SlackSession
import com.ullink.slack.simpleslackapi.SlackUser
import com.ullink.slack.simpleslackapi.replies.SlackMessageReply
import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.data.SchemaBuilder
import org.apache.kafka.connect.data.Struct
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.sink.SinkRecord
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class RecordFormattingTest {
    @Test
    fun `schemaless records flatten nested fields arrays and scalar types`() {
        val record = record(mapOf(
            "customer" to mapOf("name" to "Vegeta", "city" to "Dublin"),
            "items" to listOf(mapOf("sku" to "A1", "quantity" to 2)),
            "paid" to true,
            "total" to 12.5,
            "note" to null
        ))
        assertEquals(mapOf(
            "customer.name" to "Vegeta", "customer.city" to "Dublin",
            "items[0].sku" to "A1", "items[0].quantity" to "2",
            "paid" to "true", "total" to "12.5", "note" to "null"
        ), recordToMap(record))
    }

    @Test
    fun `schemaful records flatten nested structs and arrays`() {
        val customerSchema = SchemaBuilder.struct().field("name", Schema.STRING_SCHEMA).build()
        val schema = SchemaBuilder.struct()
            .field("customer", customerSchema)
            .field("quantities", SchemaBuilder.array(Schema.INT32_SCHEMA).build())
            .build()
        val value = Struct(schema).put("customer", Struct(customerSchema).put("name", "Vegeta"))
            .put("quantities", listOf(1, 2))
        assertEquals(mapOf("customer.name" to "Vegeta", "quantities[0]" to "1", "quantities[1]" to "2"),
            recordToMap(record(value, schema)))
    }

    @Test
    fun `tombstones and empty objects provide no template fields`() {
        assertTrue(recordToMap(record(null)).isEmpty())
        assertTrue(recordToMap(record(emptyMap<String, Any>())).isEmpty())
    }

    @Test
    fun `format replaces dotted and array fields and preserves missing placeholders`() {
        assertEquals("Dear Vegeta, 2 x A1. \${missing}", format(
            "Dear \${customer.name}, \${items[0].quantity} x \${items[0].sku}. \${missing}",
            mapOf("customer.name" to "Vegeta", "items[0].quantity" to "2", "items[0].sku" to "A1")
        ))
    }

    @Test
    fun `record content cannot expand placeholders for other fields`() {
        assertEquals("Literal \${secret}", format("\${message}", mapOf("message" to "Literal \${secret}", "secret" to "private-value")))
    }

    @Test
    fun `format preserves literal text and supports escaping`() {
        assertEquals("literal \${name}", format("literal $\${name}", mapOf("name" to "Vegeta")))
    }
}

class SlackTaskTest {
    @Test
    fun `task has public no argument constructor for Kafka Connect`() {
        assertNotNull(SlackSinkTask::class.java.getConstructor().newInstance())
    }

    @Test
    fun `channel destination receives a formatted message for every nonempty record`() {
        val fake = FakeSlack()
        val task = SlackSinkTask { token ->
            assertEquals("synthetic-test-token", token)
            fake.session
        }
        task.start(properties())
        task.put(listOf(record(mapOf("name" to "Vegeta")), record(null), record(emptyMap<String, Any>()), record(mapOf("name" to "Goku"))))
        assertEquals(listOf("connect", "findChannel:orders", "channel:Hello Vegeta", "findChannel:orders", "channel:Hello Goku"), fake.calls)
    }

    @Test
    fun `username destination sends direct messages`() {
        val fake = FakeSlack()
        val task = SlackSinkTask { fake.session }
        task.start(properties(channel = null, username = "goku"))
        task.put(listOf(record(mapOf("name" to "Vegeta"))))
        assertEquals(listOf("connect", "findUser:goku", "user:Hello Vegeta"), fake.calls)
    }

    @Test
    fun `both destinations receive the message`() {
        val fake = FakeSlack()
        val task = SlackSinkTask { fake.session }
        task.start(properties(username = "goku"))
        task.put(listOf(record(mapOf("name" to "Vegeta"))))
        assertEquals(listOf("connect", "findChannel:orders", "channel:Hello Vegeta", "findUser:goku", "user:Hello Vegeta"), fake.calls)
    }

    @Test
    fun `blank optional destination does not send when other destination is configured`() {
        val fake = FakeSlack()
        val task = SlackSinkTask { fake.session }
        task.start(properties(channel = " ", username = "goku"))
        task.put(listOf(record(mapOf("name" to "Vegeta"))))
        assertEquals(listOf("connect", "findUser:goku", "user:Hello Vegeta"), fake.calls)
    }

    @Test
    fun `empty batch is harmless before and after startup`() {
        val fake = FakeSlack()
        val task = SlackSinkTask { fake.session }
        task.put(emptyList())
        task.start(properties())
        task.put(emptyList())
        assertEquals(listOf("connect"), fake.calls)
    }

    @Test
    fun `unstarted task rejects records instead of silently dropping them`() {
        assertThrows(ConnectException::class.java) {
            SlackSinkTask { error("factory must not run") }.put(listOf(record(mapOf("name" to "Vegeta"))))
        }
    }

    @Test
    fun `startup failure propagates and releases the failed session`() {
        val fake = FakeSlack().apply { connectFailure = IOException("connection failed") }
        val task = SlackSinkTask { fake.session }
        val failure = assertThrows(ConnectException::class.java) { task.start(properties()) }
        assertSame(fake.connectFailure, failure.cause)
        task.stop()
        assertEquals(listOf("connect", "disconnect"), fake.calls)
    }

    @Test
    fun `startup cleanup failure preserves the connection failure`() {
        val fake = FakeSlack().apply {
            connectFailure = IOException("connection failed")
            disconnectFailure = IOException("cleanup failed")
        }
        val failure = assertThrows(ConnectException::class.java) { SlackSinkTask { fake.session }.start(properties()) }
        assertSame(fake.connectFailure, failure.cause)
        assertArrayEquals(arrayOf(fake.disconnectFailure), failure.cause!!.suppressed)
    }

    @Test
    fun `stop disconnects once and future sends fail`() {
        val fake = FakeSlack()
        val task = SlackSinkTask { fake.session }
        task.start(properties())
        task.stop()
        task.stop()
        assertEquals(listOf("connect", "disconnect"), fake.calls)
        assertThrows(ConnectException::class.java) { task.put(listOf(record(mapOf("name" to "Vegeta")))) }
    }

    @Test
    fun `disconnect failure propagates`() {
        val fake = FakeSlack().apply { disconnectFailure = IOException("disconnect failed") }
        val task = SlackSinkTask { fake.session }
        task.start(properties())
        val failure = assertThrows(ConnectException::class.java) { task.stop() }
        assertSame(fake.disconnectFailure, failure.cause)
    }

    @Test
    fun `transport send failure propagates and does not send the next record`() {
        val fake = FakeSlack().apply { sendFailure = IllegalStateException("send failed") }
        val task = SlackSinkTask { fake.session }
        task.start(properties())
        val failure = assertThrows(IllegalStateException::class.java) {
            task.put(listOf(record(mapOf("name" to "Vegeta")), record(mapOf("name" to "Goku"))))
        }
        assertSame(fake.sendFailure, failure)
        assertEquals(listOf("connect", "findChannel:orders", "channel:Hello Vegeta"), fake.calls)
    }

    @Test
    fun `Slack API rejection fails both channel and direct message sends`() {
        for (props in listOf(properties(), properties(channel = null, username = "goku"))) {
            val fake = FakeSlack().apply { apiError = "not_authed" }
            val task = SlackSinkTask { fake.session }
            task.start(props)
            val failure = assertThrows(ConnectException::class.java) { task.put(listOf(record(mapOf("name" to "Vegeta")))) }
            assertTrue(failure.message!!.contains("not_authed"))
        }
    }

    @Test
    fun `missing API reply cannot be treated as successful delivery`() {
        val fake = FakeSlack().apply { missingReply = true }
        val task = SlackSinkTask { fake.session }
        task.start(properties())
        assertThrows(ConnectException::class.java) { task.put(listOf(record(mapOf("name" to "Vegeta")))) }
    }

    @Test
    fun `unresolved destinations fail without calling send`() {
        for (props in listOf(properties(), properties(channel = null, username = "goku"))) {
            val fake = FakeSlack().apply { missingDestination = true }
            val task = SlackSinkTask { fake.session }
            task.start(props)
            assertThrows(ConnectException::class.java) { task.put(listOf(record(mapOf("name" to "Vegeta")))) }
            assertEquals(2, fake.calls.size)
            assertTrue(fake.calls.last().startsWith("find"))
        }
    }
}

class SlackConfigTest {
    @Test
    fun `Kafka validation reports required fields and destination rules before startup`() {
        val connector = SlackSinkConnector()
        val cases = listOf(
            (properties() - "slack.token") to "slack.token",
            (properties() + ("slack.token" to " ")) to "slack.token",
            (properties() - "message.template") to "message.template",
            (properties() + ("message.template" to " ")) to "message.template",
            properties(channel = null) to "slack.channel",
            properties(channel = " ", username = " ") to "slack.username"
        )
        for ((props, field) in cases) {
            val values = connector.validate(props).configValues()
            assertTrue("Expected validation error for $field", values.single { it.name() == field }.errorMessages().isNotEmpty())
        }
        for (props in listOf(properties(), properties(channel = null, username = "goku"))) {
            assertTrue(connector.validate(props).configValues().all { it.errorMessages().isEmpty() })
        }
    }

    @Test
    fun `token template and at least one destination are required`() {
        val valid = properties()
        val invalid = listOf(
            valid - "slack.token", valid + ("slack.token" to " "),
            valid - "message.template", valid + ("message.template" to " "),
            valid - "slack.channel", properties(channel = " ", username = " ")
        )
        for (props in invalid) {
            assertThrows(ConfigException::class.java) { SlackSinkConnectorConfig(props) }
        }
        SlackSinkConnectorConfig(properties(channel = null, username = "goku"))
        assertEquals(ConfigDef.Type.PASSWORD, SlackSinkConnectorConfig.CONFIG.configKeys()["slack.token"]!!.type)
        assertFalse(SlackSinkConnectorConfig(valid).getPassword("slack.token").toString().contains("synthetic-test-token"))
    }

    @Test
    fun `invalid task config fails before any Slack connection`() {
        val task = SlackSinkTask { error("invalid config reached session factory") }
        assertThrows(ConfigException::class.java) { task.start(properties() - "slack.token") }
    }

    @Test
    fun `connector wraps config failure`() {
        val failure = assertThrows(ConnectException::class.java) { SlackSinkConnector().start(emptyMap()) }
        assertTrue(failure.cause is ConfigException)
    }

    @Test
    fun `connector fans out independent snapshots without aliasing caller configuration`() {
        val props = properties().toMutableMap()
        val connector = SlackSinkConnector()
        connector.start(props)
        props["slack.channel"] = "changed-after-start"
        val configs = connector.taskConfigs(3)
        assertEquals(3, configs.size)
        assertTrue(configs.all { it["slack.channel"] == "orders" })
        (configs.first() as MutableMap)["slack.channel"] = "changed-task"
        assertEquals("orders", configs[1]["slack.channel"])
        assertEquals("orders", connector.taskConfigs(1).single()["slack.channel"])
        assertTrue(connector.taskConfigs(0).isEmpty())
        assertEquals(SlackSinkTask::class.java, connector.taskClass())
        assertEquals(SlackSinkTask().version(), connector.version())
        assertSame(SlackSinkConnectorConfig.CONFIG, connector.config())
    }
}

private fun record(value: Any?, schema: Schema? = null): SinkRecord = SinkRecord("orders", 0, null, null, schema, value, 0)

private fun properties(channel: String? = "orders", username: String? = null): Map<String, String> = buildMap {
    put("slack.token", "synthetic-test-token")
    put("message.template", "Hello \${name}")
    channel?.let { put("slack.channel", it) }
    username?.let { put("slack.username", it) }
}

private class FakeSlack {
    val calls = mutableListOf<String>()
    var connectFailure: IOException? = null
    var disconnectFailure: IOException? = null
    var sendFailure: RuntimeException? = null
    var apiError: String? = null
    var missingReply = false
    var missingDestination = false
    private val channel = SlackChannel()
    private val user = proxy<SlackUser> { name, _ -> error("Unexpected user method $name") }
    val session = proxy<SlackSession> { name, args ->
        when (name) {
            "connect" -> { calls += "connect"; connectFailure?.let { throw it }; null }
            "disconnect" -> { calls += "disconnect"; disconnectFailure?.let { throw it }; null }
            "findChannelByName" -> { calls += "findChannel:${args[0]}"; if (missingDestination) null else channel }
            "findUserByUserName" -> { calls += "findUser:${args[0]}"; if (missingDestination) null else user }
            "sendMessage", "sendMessageToUser" -> {
                assertSame(if (name == "sendMessage") channel else user, args[0])
                calls += "${if (name == "sendMessage") "channel" else "user"}:${args[1]}"
                sendFailure?.let { throw it }
                SlackMessageHandle<SlackMessageReply>(1).also { handle ->
                    if (!missingReply) handle.reply = SlackMessageReply(apiError == null, apiError, 1, "1.0")
                }
            }
            else -> error("Unexpected Slack method $name")
        }
    }
}

private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        call(method.name, args ?: emptyArray())
    } as T
