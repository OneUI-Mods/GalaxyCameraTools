package dev.ryuya.galaxycamera

/** A finite retry budget for each consecutive settings outage, reset by a successful read. */
internal class ConfigurationRetries {
    private var failures = 0

    fun succeeded() {
        failures = 0
    }

    fun nextDelay(): Long? {
        if (failures >= 6) return null
        failures++
        return minOf(200L shl failures, 5000L)
    }
}
