package com.crack.scenario.imports

import com.crack.global.exception.NotFoundException
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 가져오기 작업 하나. 추출 결과와 분석 결과를 함께 들고 있다. */
data class ImportJob(
    val id: String,
    val page: ExtractedPage,
    val analysis: ImportAnalysis,
    val createdAt: Instant,
)

/**
 * 추출·분석 결과를 서버 메모리에 캐시한다 (DESIGN.md §11.2).
 *
 * DB 테이블을 만들지 않는다. 확인 화면에서 생성으로 넘어가는 몇 분 동안만 살아 있으면 되고,
 * 서버를 다시 띄우면 다시 분석하는 것이 맞다(같은 URL을 두 번 내려받는 값은 싸다).
 * TTL은 `crack.import.job-ttl-minutes`(기본 30분)이고, 쓸 때마다 만료된 것을 지운다.
 */
@Component
class ImportJobStore(private val properties: ImportProperties) {

    private val jobs = ConcurrentHashMap<String, ImportJob>()

    fun put(page: ExtractedPage, analysis: ImportAnalysis): ImportJob {
        purgeExpired()
        val job = ImportJob(UUID.randomUUID().toString(), page, analysis, Instant.now())
        jobs[job.id] = job
        return job
    }

    /** 없거나 만료됐으면 404. 분석부터 다시 하라는 뜻이다. */
    fun require(jobId: String): ImportJob {
        purgeExpired()
        return jobs[jobId] ?: throw NotFoundException("가져오기 작업을 찾을 수 없습니다. 분석을 다시 해 주세요: $jobId")
    }

    fun remove(jobId: String) {
        jobs.remove(jobId)
    }

    fun size(): Int = jobs.size

    private fun purgeExpired() {
        val ttl = Duration.ofMinutes(properties.jobTtlMinutes.coerceAtLeast(1))
        val deadline = Instant.now().minus(ttl)
        jobs.entries.removeIf { it.value.createdAt.isBefore(deadline) }
    }
}
