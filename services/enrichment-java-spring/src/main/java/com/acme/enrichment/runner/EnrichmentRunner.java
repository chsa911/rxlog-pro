package com.acme.enrichment.runner;

import com.acme.enrichment.client.BookserviceClient;
import com.acme.enrichment.config.WorkerProperties;
import com.acme.enrichment.pipeline.EnrichmentPipeline;
import com.acme.enrichment.repo.EnrichmentJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.UUID;

@Component
public class EnrichmentRunner {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentRunner.class);

    private final WorkerProperties props;
    private final EnrichmentJobRepository jobs;
    private final BookserviceClient bookservice;
    private final EnrichmentPipeline pipeline;
    private final String workerId;

    public EnrichmentRunner(WorkerProperties props,
                            EnrichmentJobRepository jobs,
                            BookserviceClient bookservice,
                            EnrichmentPipeline pipeline) {
        this.props = props;
        this.jobs = jobs;
        this.bookservice = bookservice;
        this.pipeline = pipeline;
        this.workerId = buildWorkerId();
    }

    @Scheduled(fixedDelayString = "${worker.pollDelayMs:2000}")
    public void tick() {
        if (!props.enabled()) return;

        var batch = jobs.claimBatch(props.batchSize(), workerId);
        if (!batch.isEmpty()) {
            log.info("claimed {} jobs", batch.size());
        }

        for (var job : batch) {
            try {
                var input = bookservice.getInput(job.bookId());
                var patch = pipeline.enrich(input);
                bookservice.patch(job.bookId(), patch);
                jobs.markDone(job.jobId());
            } catch (Exception e) {
                int attempt = job.attempts();
                String msg = (e.getMessage() == null) ? e.toString() : e.getMessage();

                if (attempt >= props.maxAttempts()) {
                    log.warn("Job {} book {} failed permanently after {} attempts: {}", job.jobId(), job.bookId(), attempt, msg);
                    jobs.markErrorFinal(job.jobId(), msg);
                } else {
                    int delay = backoffSeconds(attempt);
                    log.warn("Job {} book {} failed attempt {} retry in {}s: {}", job.jobId(), job.bookId(), attempt, delay, msg);
                    jobs.markRetry(job.jobId(), msg, delay);
                }
            }
        }
    }

    private static int backoffSeconds(int attempts) {
        long v = (long) (30L * Math.pow(2, Math.max(0, attempts - 1)));
        return (int) Math.min(3600L, v);
    }

    private static String buildWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID();
        }
    }
}