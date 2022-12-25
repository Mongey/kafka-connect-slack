# kafka-connect-slack
A [kafka-connect](https://kafka.apache.org/documentation/#connect) sink connector, for formatting, and sending messages to Slack

![](https://mongey.net/post/kafka-connect-slack/posting.gif)

## :electric_plug: Installation
Download the [latest jar](https://circleci.com/gh/Mongey/kafka-connect-slack/) and place it in your [kafka-connect plugins](https://docs.confluent.io/current/connect/userguide.html#installing-plugins) folder.

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

* `slack.token` is the slack token for the connector to use.
* `slack.channel` defines what channel to send the message into. (Optional)
* `slack.username` defines the user to send the message to. (Optional)
* `message.template` defines the template to use for the message.

### :memo: `message.template`
Interpolate fields from the message using `${field_name}`.
Nested fields are accessible using a `.` e.g. `${customer.address.city}` 
