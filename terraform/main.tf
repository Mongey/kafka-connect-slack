variable "slack_token" {}

provider "kafka-connect" {
  url = "http://localhost:8083"
}

resource "kafka-connect_connector" "slack_sink" {
  name = "slack_sink"

  config = {
    "name"            = "slack_sink"
    "connector.class" = "net.mongey.kafka.connect.SlackSinkConnector"
    "tasks.max"       = "1"
    "topics"          = "paid-orders"
    "slack.token"     = "${var.slack_token}"

    "slack.channel"    = "shipping-requests"
    "message.template" = "${file("slack-message.tmpl")}"
  }
}
