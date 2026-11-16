package net.mongey.kafka.connect

import com.github.wnameless.json.flattener.JsonFlattener
import org.apache.commons.text.StringSubstitutor
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.errors.RetriableException
import org.apache.kafka.connect.json.JsonConverter
import org.apache.kafka.connect.sink.SinkRecord
import org.apache.kafka.connect.sink.SinkTask
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.charset.StandardCharsets

class SlackSinkTask internal constructor(private val apiBase: URI) : SinkTask() {
    constructor() : this(URI.create("https://slack.com/api/"))

    private lateinit var config: SlackSinkConnectorConfig
    private var client: SlackWebApi? = null
    private var channelId: String? = null
    private var userId: String? = null
    private var directMessageId: String? = null
    private val accepted = mutableSetOf<Delivery>()

    override fun version(): String = javaClass.`package`.implementationVersion ?: "development"

    override fun start(props: Map<String, String>) {
        config = SlackSinkConnectorConfig(props)
        client = SlackWebApi(config.token, apiBase)
        channelId = null
        userId = null
        directMessageId = null
        accepted.clear()
    }

    override fun open(partitions: Collection<TopicPartition>) {
        log.debug("Opening the task for topic partitions: {}", partitions)
    }

    override fun put(records: Collection<SinkRecord>) {
        if (records.isEmpty()) return
        val api = client ?: throw ConnectException("Slack task has not been started")
        try {
            for (record in records) {
                val data = recordToMap(record)
                if (data.isEmpty()) continue
                val message = format(config.template, data)
                val channel = config.channel?.let { destination ->
                    channelId ?: api.channelId(destination).also { channelId = it }
                }
                val directMessage = config.username?.let { destination ->
                    val user = userId ?: api.userId(destination).also { userId = it }
                    directMessageId ?: api.openDirectMessage(user).also { directMessageId = it }
                }
                for (id in listOfNotNull(channel, directMessage)) {
                    val delivery = Delivery(record.originalTopic(), record.originalKafkaPartition(), record.originalKafkaOffset(), id)
                    if (delivery in accepted) continue
                    api.postMessage(id, message)
                    accepted += delivery
                }
            }
            accepted.clear()
        } catch (e: RetriableException) {
            context?.timeout(if (e is SlackRateLimitException) e.delayMs else 1000)
            throw e
        }
    }

    override fun flush(offsets: Map<TopicPartition, OffsetAndMetadata>?) {
        log.trace("Flushing data to Slack with the following offsets: {}", offsets)
    }

    override fun close(partitions: Collection<TopicPartition>) {
        accepted.removeAll { TopicPartition(it.topic, it.partition) in partitions }
    }

    override fun stop() {
        client = null
        channelId = null
        userId = null
        directMessageId = null
        accepted.clear()
    }

    private data class Delivery(val topic: String, val partition: Int, val offset: Long, val channel: String)

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
