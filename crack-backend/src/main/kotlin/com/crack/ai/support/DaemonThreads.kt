package com.crack.ai.support

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** AI 호출용 데몬 스레드 팩토리. 앱 종료를 막지 않는다. */
internal fun daemonThreadFactory(prefix: String): ThreadFactory {
    val counter = AtomicInteger()
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
}
