package net.mongey.kafka.connect

import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.Config
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.connect.connector.Task
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.sink.SinkConnector

class SlackSinkConnector : SinkConnector() {
    private var configProperties: Map<String, String>? = null

    override fun version(): String = javaClass.`package`.implementationVersion ?: "development"

    override fun start(props: Map<String, String>) {
        try {
            SlackSinkConnectorConfig(props)
            configProperties = props.toMap()
        } catch (e: ConfigException) {
            throw ConnectException("Couldn't start due to configuration error", e)
        }
    }

    override fun taskClass(): Class<out Task> = SlackSinkTask::class.java

    override fun taskConfigs(maxTasks: Int): List<Map<String, String>> {
        val props = configProperties ?: throw ConnectException("Connector has not been started")
        return List(maxTasks) { HashMap(props) }
    }

    override fun stop() = Unit

    override fun config(): ConfigDef = SlackSinkConnectorConfig.CONFIG

    override fun validate(connectorConfigs: Map<String, String>): Config {
        val result = super.validate(connectorConfigs)
        if (result.configValues().all { it.errorMessages().isEmpty() }) {
            try {
                SlackSinkConnectorConfig(connectorConfigs)
            } catch (e: ConfigException) {
                result.configValues()
                    .filter { it.name() in setOf(SlackSinkConnectorConfig.SLACK_CHANNEL_CONFIG, SlackSinkConnectorConfig.SLACK_USER_CONFIG) }
                    .forEach { it.addErrorMessage(e.message) }
            }
        }
        return result
    }
}
