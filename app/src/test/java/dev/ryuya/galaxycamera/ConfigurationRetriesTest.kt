package dev.ryuya.galaxycamera

import org.junit.Assert.*
import org.junit.Test

class ConfigurationRetriesTest {
    @Test
    fun unrelatedOutagesDoNotConsumeALifetimeBudget() {
        val retries = ConfigurationRetries()
        repeat(20) {
            assertEquals(400L, retries.nextDelay())
            retries.succeeded()
        }
    }

    @Test
    fun persistentFailureHasABoundedBackoffAndStops() {
        val retries = ConfigurationRetries()
        assertEquals(
            listOf(400L, 800L, 1600L, 3200L, 5000L, 5000L),
            List(6) { retries.nextDelay() },
        )
        assertNull(retries.nextDelay())
    }

    @Test
    fun aLaterSuccessfulEventRestoresTheBudget() {
        val retries = ConfigurationRetries()
        repeat(8) { retries.nextDelay() }
        retries.succeeded()
        assertEquals(400L, retries.nextDelay())
    }
}
