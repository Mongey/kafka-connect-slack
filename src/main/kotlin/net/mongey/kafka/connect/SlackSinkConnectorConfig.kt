package net.mongey.kafka.connect

import org.apache.kafka.common.config.AbstractConfig
import org.apache.kafka.common.config.ConfigDef
import org.apache.kafka.common.config.ConfigException
import org.apache.kafka.common.config.types.Password

class SlackSinkConnectorConfig(props: Map<String, String>) : AbstractConfig(CONFIG, props) {
    val token: String = getPassword(SLACK_TOKEN_CONFIG).value()
    val template: String = getString(MESSAGE_TEMPLATE_CONFIG)
    val channel: String? = getString(SLACK_CHANNEL_CONFIG)?.takeUnless(String::isBlank)
    val username: String? = getString(SLACK_USER_CONFIG)?.takeUnless(String::isBlank)

    init {
        if (channel == null && username == null) {
            throw ConfigException("At least one of $SLACK_CHANNEL_CONFIG or $SLACK_USER_CONFIG is required")
        }
    }

    companion object {
        const val SLACK_TOKEN_CONFIG = "slack.token"
        const val SLACK_USER_CONFIG = "slack.username"
        const val SLACK_CHANNEL_CONFIG = "slack.channel"
        const val MESSAGE_TEMPLATE_CONFIG = "message.template"

        @JvmStatic
        val CONFIG: ConfigDef = ConfigDef()
            .define(SLACK_TOKEN_CONFIG, ConfigDef.Type.PASSWORD, ConfigDef.NO_DEFAULT_VALUE,
                { name, value ->
                    val token = (value as Password?)?.value()
                    if (token.isNullOrBlank()) throw ConfigException("$name must not be blank")
                    if (token.any { it !in '!'..'~' }) throw ConfigException("$name must contain only visible ASCII characters")
                }, ConfigDef.Importance.HIGH, "Slack authentication token")
            .define(SLACK_USER_CONFIG, ConfigDef.Type.STRING, null, ConfigDef.Importance.HIGH, "Send to this Slack username")
            .define(SLACK_CHANNEL_CONFIG, ConfigDef.Type.STRING, null, ConfigDef.Importance.HIGH, "Send to this Slack channel")
            .define(MESSAGE_TEMPLATE_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE,
                { name, value ->
                    if ((value as String?).isNullOrBlank()) throw ConfigException("$name must not be blank")
                }, ConfigDef.Importance.HIGH, "Message template using record fields")
    }
}
