package net.mongey.kafka.connect

import org.apache.kafka.common.config.AbstractConfig
import org.apache.kafka.common.config.ConfigDef

class SlackSinkConnectorConfig : AbstractConfig  {
    constructor(props: Map<String, String>) : super(CONFIG, props)

    companion object {
        @JvmStatic
        val CONFIG = baseConfigDef()

        val SLACK_TOKEN_CONFIG = "slack.token"
        val SLACK_USER_CONFIG = "slack.username"
        val SLACK_CHANNEL_CONFIG = "slack.channel"
        val MESSAGE_TEMPLATE_CONFIG = "message.template"

        protected fun baseConfigDef(): ConfigDef {
            val configDef = ConfigDef()
            addConnectorConfigs(configDef)
            return configDef
        }

        fun addConnectorConfigs(configDef: ConfigDef) {
            val group = "Connector"
            var order = 0
            configDef.define(
                    SLACK_TOKEN_CONFIG,
                    ConfigDef.Type.STRING,
                    null,
                    ConfigDef.Importance.HIGH,
                    "It's the most importatnt thing",
                    group,
                    ++order,
                    ConfigDef.Width.SHORT,
                    "Slack Token"
            ).define(
                    SLACK_USER_CONFIG,
                    ConfigDef.Type.STRING,
                    null,
                    ConfigDef.Importance.HIGH,
                    "Send to user",
                    group,
                    ++order,
                    ConfigDef.Width.SHORT,
                    "Slack User"
            ).define(
                    SLACK_CHANNEL_CONFIG,
                    ConfigDef.Type.STRING,
                    null,
                    ConfigDef.Importance.HIGH,
                    "Send to channel",
                    group,
                    ++order,
                    ConfigDef.Width.SHORT,
                    "Slack User"
            ).define(
                    MESSAGE_TEMPLATE_CONFIG,
                    ConfigDef.Type.STRING,
                    null,
                    ConfigDef.Importance.HIGH,
                    "Template to use",
                    group,
                    ++order,
                    ConfigDef.Width.SHORT,
                    "Template"
            )
        }
    }
}