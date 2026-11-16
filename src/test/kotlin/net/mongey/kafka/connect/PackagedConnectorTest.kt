package net.mongey.kafka.connect

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.jar.JarFile

class PackagedConnectorTest {
    @Test
    fun `distributed jar loads with only Kafka worker dependencies`(): Unit = SlackHttp().use { slack ->
        val plugin = File(System.getProperty("plugin.jar"))
        JarFile(plugin).use { jar ->
            assertFalse(jar.entries().asSequence().any { it.name.startsWith("org/apache/kafka/") })
            assertFalse(jar.entries().asSequence().any { it.name.startsWith("org/slf4j/") })
            assertNotNull(jar.getEntry("kotlin/Unit.class"))
            for (prefix in listOf("com/ullink/", "org/glassfish/tyrus/", "javax/websocket/", "org/apache/http/", "com/google/gson/")) {
                assertFalse("Legacy dependency remains in JAR: $prefix", jar.entries().asSequence().any { it.name.startsWith(prefix) })
            }
            assertNotNull(jar.getEntry("net/mongey/kafka/connect/SlackWebApi.class"))
            assertNotNull(jar.getEntry("tools/jackson/databind/json/JsonMapper.class"))
        }
        val host = System.getProperty("kafka.classpath").split(File.pathSeparator).map(::File)
        val urls = (listOf(plugin) + host).map { it.toURI().toURL() }.toTypedArray()
        URLClassLoader(urls, ClassLoader.getPlatformClassLoader()).use { loader ->
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            thread.contextClassLoader = loader
            try {
                val connectorClass = loader.loadClass("net.mongey.kafka.connect.SlackSinkConnector")
                val connector = connectorClass.getConstructor().newInstance()
                val connectorApi = loader.loadClass("org.apache.kafka.connect.sink.SinkConnector")
                assertTrue(ServiceLoader.load(connectorApi, loader).any { it.javaClass == connectorClass })
                JarFile(plugin).use { jar ->
                    assertEquals(jar.manifest.mainAttributes.getValue("Implementation-Version"),
                        connectorClass.getMethod("version").invoke(connector))
                }
                val props = mapOf(
                    "slack.token" to "test-token",
                    "slack.channel" to "orders",
                    "message.template" to "Hello \${name}"
                )
                connectorClass.getMethod("start", Map::class.java).invoke(connector, props)
                val taskClass = connectorClass.getMethod("taskClass").invoke(connector) as Class<*>
                assertNotNull(taskClass.getConstructor().newInstance())
                assertEquals(plugin.toURI().toURL(), taskClass.protectionDomain.codeSource.location)

                val schema = loader.loadClass("org.apache.kafka.connect.data.Schema")
                val recordClass = loader.loadClass("org.apache.kafka.connect.sink.SinkRecord")
                val record = recordClass.getConstructor(
                    String::class.java, Int::class.javaPrimitiveType, schema, Any::class.java,
                    schema, Any::class.java, Long::class.javaPrimitiveType
                ).newInstance("orders", 0, null, null, null, mapOf("name" to "Ada"), 0L)
                val helpers = loader.loadClass("net.mongey.kafka.connect.SlackSinkTaskKt")
                val fields = helpers.getMethod("recordToMap", recordClass).invoke(null, record)
                assertEquals("Hello Ada", helpers.getMethod("format", String::class.java, Map::class.java)
                    .invoke(null, props.getValue("message.template"), fields))

                val apiClass = loader.loadClass("net.mongey.kafka.connect.SlackWebApi")
                assertEquals(plugin.toURI().toURL(), apiClass.protectionDomain.codeSource.location)
                val task = taskClass.getConstructor(java.net.URI::class.java).newInstance(slack.base)
                taskClass.getMethod("start", Map::class.java).invoke(task, props)
                taskClass.getMethod("put", Collection::class.java).invoke(task, listOf(record))
                taskClass.getMethod("stop").invoke(task)
                assertEquals(listOf("conversations.list", "chat.postMessage"), slack.requests.map { it.method })
                assertEquals(mapOf("channel" to "CORDERS", "text" to "Hello Ada"), slack.requests.last().json())
                assertEquals("Bearer test-token", slack.requests.last().authorization)
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }
}
