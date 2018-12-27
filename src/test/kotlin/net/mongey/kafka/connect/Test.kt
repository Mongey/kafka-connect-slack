package net.mongey.kafka.connect

import org.junit.Test
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.MatcherAssert.assertThat

class TestDetector {
    @Test
    fun test() {
        val template = "Dear \${name}, your \${ability} is over \${numbers}!"
        val data = hashMapOf("name" to "Vegeta", "ability" to "power", "numbers" to "9000")

        val expected = "Dear Vegeta, your power is over 9000!"
        val actual = format(template, data)
        assertThat(actual, `is`(expected))
    }
}