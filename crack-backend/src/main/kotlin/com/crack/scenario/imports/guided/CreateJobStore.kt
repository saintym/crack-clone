package com.crack.scenario.imports.guided

import com.crack.scenario.imports.JobCache
import org.springframework.stereotype.Component

/**
 * 진행 중인 "질문으로 만들기" 작업을 서버 메모리에 캐시한다 (DESIGN.md §11.6).
 *
 * TTL은 `crack.create.job-ttl-minutes`(기본 30분)다. 사용자가 질문에 답하는 동안만 살아 있으면 된다.
 * 만료되면 404고, 처음(씨앗 입력)부터 다시 한다 — 중간 상태를 DB에 남기지 않는다.
 */
@Component
class CreateJobStore(properties: CreateProperties) {

    private val cache = JobCache<CreateJob>(
        ttlMinutes = { properties.jobTtlMinutes },
        notFound = { "생성 작업을 찾을 수 없습니다. 처음부터 다시 시작해 주세요: $it" },
    )

    fun create(seed: String): CreateJob {
        val job = CreateJob(id = cache.newId(), seed = seed)
        return cache.put(job.id, job)
    }

    /** 라운드가 늘거나 미리보기가 생기면 통째로 갈아 끼운다. TTL은 처음 만든 시각을 유지한다. */
    fun save(job: CreateJob): CreateJob = cache.replace(job.id, job)

    /** 없거나 만료됐으면 404. */
    fun require(jobId: String): CreateJob = cache.require(jobId)

    fun remove(jobId: String) = cache.remove(jobId)

    fun size(): Int = cache.size()
}
