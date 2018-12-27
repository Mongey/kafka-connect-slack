package net.mongey.kafka.connect

import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.connect.connector.Task
import org.apache.kafka.connect.errors.ConnectException
import org.apache.kafka.connect.sink.SinkConnector
import java.util.ArrayList
import java.util.HashMap

class SlackSinkConnector() : SinkConnector() {
    private var configProperties: Map<String, String>? = null

    override fun version(): String {
        return "0.0.1"
    }

    @Throws(ConnectException::class)
    override fun start(props: Map<String, String>) {
        try {
            configProperties = props
            // validation
            SlackSinkConnectorConfig(props)
        } catch (e: ConfigException) {
            throw ConnectException(
                    "Couldn't start due to configuration error",
                    e
            )
        }

    }

    override fun taskClass(): Class<out Task> {
        return SlackSinkTask::class.java
    }

    override fun taskConfigs(maxTasks: Int): List<Map<String, String>> {
        val taskConfigs = ArrayList<Map<String, String>>()
        val taskProps = HashMap<String, String>()
        taskProps.putAll(configProperties!!)
        for (i in 0 until maxTasks) {
            taskConfigs.add(taskProps)
        }
        return taskConfigs
    }

    @Throws(ConnectException::class)
    override fun stop() {

    }

    override fun config(): ConfigDef {
        return SlackSinkConnectorConfig.CONFIG
    }
}

