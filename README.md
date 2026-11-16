# kafka-connect-slack
A [kafka-connect](https://kafka.apache.org/documentation/#connect) sink connector, for formatting, and sending messages to Slack

![](https://mongey.net/post/kafka-connect-slack/posting.gif)

## :electric_plug: Installation
Download the [latest jar](https://github.com/Mongey/kafka-connect-slack/releases/latest) and place it in your [kafka-connect plugins](https://docs.confluent.io/current/connect/userguide.html#installing-plugins) folder.

This source targets Java 17 or newer and Kafka Connect 4.4. Build it with JDK 21.
The plugin JAR bundles its libraries. Kafka Connect supplies Kafka APIs, JSON conversion, and logging.

Messages use Slack's Web API with a modern Slack app token. No RTM connection,
WebSocket service, or classic app is required. See the
[migration guide](docs/slack-web-api-migration.md) for app permissions and token replacement.

## Build and verify

```sh
./gradlew clean build
```

The tests need no broker, Slack credentials, or Docker. They feed real Kafka
`SinkRecord` values through conversion and templating into real HTTP requests to a
local fake Slack server. They check routing, configuration, lifecycle, and failure handling. A separate check
loads the actual plugin JAR with only Kafka worker dependencies to verify packaging
and service discovery. No test sends messages to Slack.

The test report is `build/reports/tests/test/index.html`.
The installable artifact is `build/libs/kafka-connect-slack-sink-0.1-SNAPSHOT.jar`.
See [maintenance review](docs/maintenance-review.md) for version choices and verification limits.

## :hammer_and_wrench: Configuration

```hcl
resource "kafka-connect_connector" "slack_sink" {
  name = "slack_sink"

  config = {
    "name"             = "slack_sink"
    "connector.class"  = "net.mongey.kafka.connect.SlackSinkConnector"
    "topics"           = "paid-orders"
    "slack.token"      = "${var.slack_token}"
    "slack.channel"    = "shipping-requests"
    "message.template" = "${customer.firstname} created a new order"
  }
}
```

* `slack.token` is the installed Slack app's bot token.
* `slack.channel` accepts a channel name or conversation ID. (Optional)
* `slack.username` accepts a Slack username or user ID for direct messages. (Optional)
* `message.template` defines the template to use for the message.

The token and a nonblank template are required. Configure at least one channel or
username. When both are configured, each record goes to both destinations.
IDs avoid directory lookups and need fewer permissions. Names resolve on the first
message and remain cached until the task restarts.

### :memo: `message.template`
Interpolate fields from the message using `${field_name}`.
Nested fields are accessible using a `.` e.g. `${customer.address.city}` 
