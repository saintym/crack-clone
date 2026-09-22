package com.crack.ai

import com.crack.ai.provider.StreamListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 테스트용 리스너: 호출 순서를 기록하고 종료 콜백을 기다릴 수 있다. */
class RecordingListener : StreamListener {
    val deltas = CopyOnWriteArrayList<String>()
    val completes = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<Throwable>()
    private val done = CountDownLatch(1)

    override fun onDelta(text: String) {
        deltas.add(text)
    }

    override fun onComplete(fullText: String) {
        completes.add(fullText)
        done.countDown()
    }

    override fun onError(error: Throwable) {
        errors.add(error)
        done.countDown()
    }

    fun await(seconds: Long = 5): Boolean = done.await(seconds, TimeUnit.SECONDS)
}
