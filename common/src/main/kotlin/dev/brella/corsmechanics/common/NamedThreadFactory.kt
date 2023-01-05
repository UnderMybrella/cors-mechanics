package dev.brella.corsmechanics.common

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

class NamedThreadFactory(namePrefix: String) : ThreadFactory {
    companion object {
        private val poolNumber = AtomicInteger(1)
    }

    private val group: ThreadGroup
    private val threadNumber = AtomicInteger(1)
    private val namePrefix: String

    override fun newThread(r: Runnable): Thread {
        val t = Thread(
            group, r,
            namePrefix + threadNumber.getAndIncrement(),
            0
        )
        if (t.isDaemon) t.isDaemon = false
        if (t.priority != Thread.NORM_PRIORITY) t.priority = Thread.NORM_PRIORITY
        return t
    }

    init {
        val s = System.getSecurityManager()

        this.group = s?.threadGroup ?: Thread.currentThread().threadGroup
        this.namePrefix = "$namePrefix${poolNumber.getAndIncrement()}-thread-"
    }
}