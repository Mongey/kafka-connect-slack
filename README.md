# kafka-connect-slack

A Kafka Connect sink that formats Kafka records and sends messages to Slack channels
and direct messages through the Slack Web API.

![Example formatted order notifications in Slack](https://mongey.net/post/kafka-connect-slack/posting.gif)

## Requirements and installation

This source targets Kafka Connect 4.4 on Java 17 or newer. Build it with JDK 21.
It uses a modern Slack app bot token; RTM, WebSockets, and classic apps are no longer
required. Existing installations should follow the [migration guide](docs/slack-web-api-migration.md).

Build this source version:

```sh
./gradlew clean build
```

Copy `build/libs/kafka-connect-slack-sink-0.1-SNAPSHOT.jar` into a dedicated directory
under each worker's configured `plugin.path`, then restart the workers. The JAR
bundles its libraries; Kafka Connect supplies Kafka APIs, JSON conversion, and logging.

For published builds, use [GitHub Releases](https://github.com/Mongey/kafka-connect-slack/releases)
and follow the requirements for that release. A published JAR may predate the source
changes described here.

## Set up Slack

1. Create and install a modern Slack app with the `chat:write` bot scope.
2. Invite the app to the destination channel and obtain its conversation ID.
3. Supply the app's bot token as `slack.token` and the ID as `slack.channel`.

Using channel IDs avoids directory lookups. Channel-name lookup also needs
`channels:read` and `groups:read`. Direct messages need `im:write`, plus `users:read`
when resolving a username. See the [destination and scope table](docs/slack-web-api-migration.md#choose-destinations-and-permissions).

## Configuration

With your Kafka Connect Terraform provider configured, this example consumes
schemaless JSON records and sends notifications to a channel:

```hcl
resource "kafka-connect_connector" "slack_sink" {
  name = "slack_sink"

  config = {
    "name"                           = "slack_sink"
    "connector.class"                = "net.mongey.kafka.connect.SlackSinkConnector"
    "topics"                         = "paid-orders"
    "value.converter"                = "org.apache.kafka.connect.json.JsonConverter"
    "value.converter.schemas.enable" = "false"
    "slack.token"                    = var.slack_token
    "slack.channel"                  = "C012AB3CD"
    "message.template"               = "$${customer.firstname} created a new order"
  }
}
```

Replace the example conversation ID and supply `var.slack_token` through your
existing secret-management setup. Terraform requires
[`$${` to preserve a literal `${`](https://developer.hashicorp.com/terraform/language/expressions/strings#escape-sequences).
In connector JSON configuration or a template read with Terraform's `file()`
function, use `${customer.firstname}` directly.

| Setting | Required | Meaning |
| --- | --- | --- |
| `slack.token` | Yes | Installed Slack app's bot token |
| `message.template` | Yes | Nonblank template using record fields |
| `slack.channel` | One destination required | Channel name, optional `#` prefix, or conversation ID |
| `slack.username` | One destination required | Slack username, optional `@` prefix, or user ID for direct messages |

Configure either destination or both. With both configured, each record goes to
the channel and then to the direct message. Names resolve on the first nonempty
record and remain cached until the task restarts. Usernames match Slack's `name`
field, not display names or email addresses.

The JSON converter settings above expect a JSON object without a schema envelope.
For example, this record produces `Ada created a new order`:

```json
{"customer":{"firstname":"Ada"}}
```

For schemaful records, retain the converter settings appropriate to your topic.

### Message templates

Use `${field_name}` to interpolate fields. Nested fields use dots, such as
`${customer.address.city}`. Array elements use indexes, such as `${items[0].sku}`.
Unknown placeholders remain literal. Placeholder-like text inside record values
is not expanded again. Tombstones and empty objects are skipped.

## Delivery and failures

Startup checks configuration locally. Slack authentication and destination access
are checked when the first nonempty record is delivered, so an idle task's RUNNING
status does not prove its token works.

Rate limits and temporary server or transport failures ask Kafka Connect to retry.
HTTP 429 responses use Slack's `Retry-After` delay. Permanent API errors, unresolved
destinations, and malformed replies fail the task instead of silently discarding
records.

Confirmed sends are remembered while retrying a failed batch. Delivery remains
at least once: task restarts, uncommitted offsets, or lost responses can still
produce duplicates. See [retry behavior](docs/slack-web-api-migration.md#understand-failures-and-retries).

## Verify locally

```sh
./gradlew clean build --no-daemon --warning-mode all
```

The tests need no broker, Slack credentials, or Docker. They feed real Kafka
`SinkRecord` values through conversion and templating into HTTP requests to a local
fake Slack server. They check routing, configuration, pagination, lifecycle,
response validation, and retry behavior.

A separate test loads the built JAR with only Kafka worker dependencies, verifies
plugin discovery, and delivers a message through HTTP. No test sends messages to
Slack. Live workspace authentication, Kafka worker offset commits, and rebalance
recovery are not covered by this local harness.

Open `build/reports/tests/test/index.html` for results. See the
[migration verification notes](docs/slack-web-api-migration.md#verify-the-migration-locally)
for the Java 17 and 21 checks, or the [earlier maintenance review](docs/maintenance-review.md)
for the pre-migration baseline.
