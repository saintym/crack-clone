package com.crack.scenario.imports

import org.springframework.stereotype.Component
import java.time.Instant

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
 * 캐시 동작은 [JobCache]가 한다. TTL은 `crack.import.job-ttl-minutes`(기본 30분)이다.
 * 같은 URL을 두 번 내려받는 값은 싸므로 서버를 다시 띄우면 다시 분석하는 것이 맞다.
 */
@Component
class ImportJobStore(properties: ImportProperties) {

    private val cache = JobCache<ImportJob>(
        ttlMinutes = { properties.jobTtlMinutes },
        notFound = { "가져오기 작업을 찾을 수 없습니다. 분석을 다시 해 주세요: $it" },
    )

    fun put(page: ExtractedPage, analysis: ImportAnalysis): ImportJob {
        val job = ImportJob(cache.newId(), page, analysis, Instant.now())
        return cache.put(job.id, job)
    }

    /** 없거나 만료됐으면 404. 분석부터 다시 하라는 뜻이다. */
    fun require(jobId: String): ImportJob = cache.require(jobId)

    fun remove(jobId: String) = cache.remove(jobId)

    fun size(): Int = cache.size()
}
