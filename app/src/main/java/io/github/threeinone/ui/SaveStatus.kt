package io.github.threeinone.ui

internal class SaveStatus {
    enum class Phase { IDLE, SAVING, FAILED, SAVED }
    var phase = Phase.IDLE
        private set
    var generation = 0L
        private set
    var revision = 0L
        private set

    fun pending(): Long {
        phase = Phase.SAVING
        return ++generation
    }

    fun loaded(revision: Long) {
        if (phase == Phase.IDLE && revision > 0) {
            this.revision = revision
            phase = Phase.SAVED
        }
    }

    fun complete(generation: Long, revision: Long, success: Boolean): Boolean {
        if (generation != this.generation) return false
        phase = if (success) Phase.SAVED else Phase.FAILED
        if (success) this.revision = revision
        return true
    }
}
