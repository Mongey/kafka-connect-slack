# Harness and maintenance review

This records the maintenance baseline in commit `65ae445`. The subsequent
[Web API migration](slack-web-api-migration.md) replaces the legacy transport and
its client mocks with HTTP contract tests. Version and test counts below describe
that earlier baseline.

Reviewed on 2026-11-16. The initial suite had one string-formatting test. It did not
start a connector or task, convert a Kafka record, observe Slack sends, or load the
distributed JAR.

## Version choices

| Component | Previous | Updated |
| --- | --- | --- |
| Kotlin | 1.3.70 | 2.4.21 |
| Gradle | 4.10.3 | 9.7.0 |
| Kafka Connect APIs | 2.1.0 | 4.4.0 |
| Simple Slack API | 1.0.0 | 1.4.0 |
| Commons Text | 1.6 | 1.15.0 |
| JSON Flattener | 0.6.0 | 0.18.6 under `com.github.wnameless.json` |
| Tyrus standalone client | 1.12 | 1.22 |
| JUnit | 4.12 | 4.13.2 |
| JVM bytecode target | 6 | 17 |
| CI JDK | 19 | 21 |

[Kotlin's release page](https://kotlinlang.org/releases.html) lists 2.4.21 as stable.
Gradle 9.7.0 is the maximum fully supported version in the
[Kotlin compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html).
The wrapper verifies the distribution checksum. Maven Central metadata was checked
for the library releases. Deprecated JCenter and the unused HTTP Confluent repository
were removed.

Tyrus stays on its `javax.websocket` line because Simple Slack API imports that
namespace. Tyrus 2.x changes to `jakarta.websocket`. The duplicate Grizzly transport
and WebSocket API dependencies were removed. Gson, HttpClient, HttpMime, and
ThreeTenBP receive compatible updates through dependency constraints. Commons Codec
and Commons Logging are updated to 1.23.0 and 1.4.0 respectively.
JUnit stays on its latest 4.x patch because the existing test runner meets this
harness's needs.

## Findings and changes

- The upgraded compiler exposed configuration initialization before key fields,
  producing `Configuration null is defined twice`. Compile-time constants remove
  that initialization dependency.
- Configuration previously accepted a missing token, template, and destination.
  Startup and Kafka's connector-validation API now validate those inputs. The token uses Kafka's password type so
  configuration logging masks it.
- Connection exceptions were swallowed, send responses were ignored, and shutdown
  did not disconnect. The task now reports failures and releases the session.
- Each task configuration previously shared one mutable map. Task configurations
  now have independent copies.
- Template expansion used a deprecated class. Commons Text handles substitution,
  with interpolation inside record values disabled so record content stays literal.
- The old artifact bundled Kafka and logging APIs. They now remain worker-provided.
  The plugin declares its service provider for Kafka's service-loader discovery.

## Harness design

The task accepts a factory for the existing `SlackSession` interface and keeps a
public no-argument constructor for Kafka. A recording proxy observes messages and
simulates failures without credentials. This keeps conversion and routing under
test without introducing another production abstraction.

A local Slack HTTP server would not cover the existing RTM transport without also
simulating its connection and WebSocket protocol. That larger harness belongs with
the future transport migration. A connector-owned messenger interface was also
considered but is unnecessary for this maintenance step.

The packaged-JAR test uses an isolated classloader containing the built artifact
and Kafka worker libraries. It checks service discovery, reflective construction,
configuration startup, record conversion, templating, and the WebSocket provider.
It also checks that Kafka and SLF4J classes are absent from the plugin JAR.

## Verification evidence

`./gradlew clean build --no-daemon --warning-mode all` passed on JDK 21. The suite
ran 27 tests with no failures, errors, or skipped tests. The test groups contain
6 conversion/formatting tests, 15 task tests, 5 configuration tests, and 1
packaged-JAR test. The full build produced no compiler or Gradle deprecation warnings.

Before the configuration fix, the packaged test failed on the duplicate null
configuration key. Before the null conversion fix, the record test failed on a
null field. Both pass with the final code.

The resulting JAR contains no Kafka or SLF4J classes. An inspection of base class
files found no bytecode requiring a newer runtime than Java 17. Runtime execution
was tested on Java 21, not Java 17. Reports are in `build/reports/tests/test/` and
JUnit XML is in `build/test-results/test/`.

## Remaining limits

The harness proves local contracts and artifact loading. It does not run a Kafka
worker, exercise offset commits or rebalance recovery, or authenticate with Slack.
It does not prove that the legacy Slack client works with a current workspace.
Slack's [classic-app retirement notice](https://api.slack.com/changelog/2024-09-legacy-custom-bots-classic-apps-deprecation)
lists November 2026. A modern Slack Web API transport is follow-up work.

A task failure prevents silent success, but no retry or deduplication policy is
added. Delivery is not exactly once. If one destination succeeds and another fails,
replaying the record can duplicate the successful send.

Java 8 and older Kafka Connect workers are outside the new supported baseline.
No release or live Slack message was sent during this work.
