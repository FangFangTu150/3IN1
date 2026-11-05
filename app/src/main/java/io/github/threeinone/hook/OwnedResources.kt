package io.github.threeinone.hook

/** Failed releases remain owned, so a later cleanup cannot silently double-register. */
internal class OwnedResources {
    private val releases = linkedMapOf<String, () -> Unit>()
    val pending: Set<String> get() = synchronized(this) { releases.keys.toSet() }

    @Synchronized fun add(name: String, release: () -> Unit) {
        check(name !in releases) { "Resource already owned: $name" }
        releases[name] = release
    }

    @Synchronized fun releaseAll(): List<String> {
        val failures = mutableListOf<String>()
        for ((name, release) in releases.toList().asReversed()) {
            try {
                release()
                releases.remove(name)
            } catch (error: Throwable) {
                failures += "$name: ${error.javaClass.simpleName}"
            }
        }
        return failures
    }

    fun <T> install(block: () -> T): T = try { block() } catch (error: Throwable) {
        val failures = releaseAll()
        if (failures.isNotEmpty()) error.addSuppressed(IllegalStateException("Cleanup pending: $failures"))
        throw error
    }
}
