package net.mongey.kafka.connect

import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.data.SchemaBuilder
import org.apache.kafka.connect.data.Struct
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.sink.SinkRecord
import org.junit.Assert.*
import org.junit.Test

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

class SlackConfigTest {
    @Test
    fun `invalid token characters fail safely before HTTP header construction`() {
        for (separator in listOf("\n", "\r", "\t", " ", "é")) {
            val token = "synthetic-private-token${separator}suffix"
            val props = properties() + ("slack.token" to token)
            val errors = SlackSinkConnector().validate(props).configValues()
                .single { it.name() == "slack.token" }.errorMessages()
            assertTrue(errors.isNotEmpty())
            assertTrue(errors.none { it.contains("synthetic-private-token") })
            val failure = assertThrows(ConfigException::class.java) { SlackSinkTask().start(props) }
            assertFalse(failure.toString().contains("synthetic-private-token"))
            assertNull(failure.cause)
        }
        assertEquals("synthetic-private-token", SlackSinkConnectorConfig(
            properties() + ("slack.token" to "synthetic-private-token\n")
        ).token)
    }

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
        val task = SlackSinkTask()
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
