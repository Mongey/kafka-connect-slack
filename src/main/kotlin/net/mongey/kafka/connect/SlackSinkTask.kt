package net.mongey.kafka.connect

import com.github.wnameless.json.flattener.JsonFlattener
import com.ullink.slack.simpleslackapi.SlackMessageHandle
import com.ullink.slack.simpleslackapi.SlackSession
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory
import com.ullink.slack.simpleslackapi.replies.SlackMessageReply
import org.apache.commons.text.StringSubstitutor
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.json.JsonConverter
import org.apache.kafka.connect.sink.SinkRecord
import org.apache.kafka.connect.sink.SinkTask
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets

class SlackSinkTask @JvmOverloads constructor(
    private val sessionFactory: (String) -> SlackSession = SlackSessionFactory::createWebSocketSlackSession
) : SinkTask() {
    private lateinit var config: SlackSinkConnectorConfig
    private var session: SlackSession? = null

    override fun version(): String = javaClass.`package`.implementationVersion ?: "development"

    override fun start(props: Map<String, String>) {
        log.info("Starting SlackSinkTask.")
        config = SlackSinkConnectorConfig(props)
        val client = sessionFactory(config.token)
        try {
            client.connect()
        } catch (e: IOException) {
            try {
                client.disconnect()
            } catch (cleanup: IOException) {
                e.addSuppressed(cleanup)
            }
            throw ConnectException("Unable to connect to Slack", e)
        }
        session = client
    }

    override fun open(partitions: Collection<TopicPartition>) {
        log.debug("Opening the task for topic partitions: {}", partitions)
    }

    override fun put(records: Collection<SinkRecord>) {
        if (records.isEmpty()) return
        val client = session ?: throw ConnectException("Slack task has not been started")
        for (record in records) {
            val data = recordToMap(record)
            if (data.isEmpty()) continue
            val message = format(config.template, data)
            config.channel?.let { name ->
                val channel = client.findChannelByName(name)
                    ?: throw ConnectException("Slack channel not found: $name")
                checkReply(client.sendMessage(channel, message))
            }
            config.username?.let { name ->
                val user = client.findUserByUserName(name)
                    ?: throw ConnectException("Slack user not found: $name")
                checkReply(client.sendMessageToUser(user, message, null))
            }
        }
    }

    private fun checkReply(handle: SlackMessageHandle<SlackMessageReply>) {
        val reply = handle.reply ?: throw ConnectException("Slack returned no message reply")
        if (!reply.isOk) throw ConnectException("Slack rejected message: ${reply.errorMessage}")
    }

    override fun flush(offsets: Map<TopicPartition, OffsetAndMetadata>?) {
        log.trace("Flushing data to Slack with the following offsets: {}", offsets)
    }

    override fun close(partitions: Collection<TopicPartition>) {
        log.debug("Closing the task for topic partitions: {}", partitions)
    }

    override fun stop() {
        val client = session ?: return
        session = null
        try {
            client.disconnect()
        } catch (e: IOException) {
            throw ConnectException("Unable to disconnect from Slack", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SlackSinkTask::class.java)
    }
}

fun recordToMap(record: SinkRecord): Map<String, String> {
    val converter = JsonConverter()
    converter.configure(mapOf("schemas.enable" to false), false)
    val payload = converter.fromConnectData(record.topic(), record.valueSchema(), record.value())
        ?: return emptyMap()
    val flattened = JsonFlattener.flattenAsMap(String(payload, StandardCharsets.UTF_8))
    return buildMap {
        for ((key, value) in flattened) put(key, value?.toString() ?: "null")
    }
}

fun format(template: String, data: Map<String, String>): String = StringSubstitutor(data).setDisableSubstitutionInValues(true).replace(template)
