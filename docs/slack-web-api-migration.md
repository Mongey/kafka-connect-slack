# Migrate to the Slack Web API

This connector now sends messages through `chat.postMessage`. It no longer uses
the Simple Slack API library, RTM, or WebSockets. Existing configuration keys and
message templates remain supported.

## Replace the legacy token

1. Create a modern Slack app in the destination workspace.
2. Add the bot token scopes needed for your destinations, listed below.
3. Install or reinstall the app into the workspace and obtain its bot token.
4. Invite the app to the channels where it will post.
5. Replace `slack.token` in the connector configuration and restart the connector
   with the new plugin JAR.

The connector does not create or install a Slack app. A classic-app token is not
converted automatically. Keep the token in your Kafka Connect secret provider or
existing secret-management system.
Invalid embedded whitespace and non-ASCII token characters are rejected with a
sanitized configuration error before HTTP headers are constructed.

## Choose destinations and permissions

All messages require [`chat:write`](https://docs.slack.dev/reference/methods/chat.postMessage/).
The table lists additional permissions used to resolve destinations.

| Configuration | Example | Additional bot scopes |
| --- | --- | --- |
| `slack.channel` with a conversation ID | `C012AB3CD` | None for lookup |
| `slack.channel` with a channel name | `shipping-requests` or `#shipping-requests` | `channels:read` and `groups:read` for public/private channel lookup |
| `slack.username` with a user ID | `U012A3CDE` | `im:write` |
| `slack.username` with a username | `alice` or `@alice` | `users:read` and `im:write` |

Channel IDs can begin with `C`, `G`, or `D`. User IDs can begin with `U` or `W`.
Prefer IDs because they survive renames and avoid directory lookup permissions.
The app must still have permission to post to the destination.

Channel-name lookup uses paginated
[`conversations.list`](https://docs.slack.dev/reference/methods/conversations.list/)
over public and private channels visible to the app. Username lookup matches the
`name` field returned by [`users.list`](https://docs.slack.dev/reference/methods/users.list/).
Display names and email addresses are not used as fallback matches.
Direct messages open or resume a conversation using
[`conversations.open`](https://docs.slack.dev/reference/methods/conversations.open/).

Successful lookups are cached for the task's lifetime. Restart the task to resolve
a changed name or workspace membership again. Configuring both destinations sends
each nonempty record to the channel and then to the direct message.

## Understand failures and retries

Startup validates configuration locally. Authentication and destination access are
checked by Slack when the first nonempty record is delivered. An idle task's
RUNNING status therefore does not prove that its token works.

Slack API rejections, unknown names, and malformed responses fail delivery instead
of silently acknowledging records. HTTP 429 rate limits use Slack's
[`Retry-After` header](https://docs.slack.dev/apis/web-api/rate-limits/) to request a
retry delay from Kafka Connect. Temporary server and transport failures also
surface as retryable errors. The connector does not sleep or run its own retry loop.

The task remembers successful deliveries while retrying a failed batch. It skips
those sends on the next attempt so a rate limit on a later message does not cause
the batch to repeatedly send its first message. This progress is cleared after a
successful batch, on partition revocation, and when the task stops.

Delivery remains at least once, not exactly once. Restarting the task loses its
in-memory progress, and a response can be lost after Slack accepts a message.
Kafka can also replay records whose offsets were not committed. These cases can
still produce duplicate messages.

## Verify the migration locally

```sh
./gradlew clean build --no-daemon --warning-mode all
```

The harness drives the real task and HTTP transport against an in-process fake
Slack server. Requests include the actual HTTP method, authorization header,
encoded query parameters, and JSON body. Tests exercise pagination, destination
caching, response validation, and retry classification without real credentials.
The packaged-JAR test also delivers through HTTP from an isolated classloader.

On 2026-11-16, all 39 tests passed on JDK 21 and again with Gradle's test launcher
set to Temurin 17.0.21. The compiler also uses `-Xjdk-release=17` to reject calls to
newer JDK APIs. The built JAR test confirms that the legacy Slack, WebSocket,
Tyrus, Apache HTTP, and Gson classes are absent.

This verifies the connector's local HTTP contract. It does not authenticate with
a live Slack workspace or exercise Kafka worker offset commits and rebalance
recovery. No real Slack message is sent by the test suite.
