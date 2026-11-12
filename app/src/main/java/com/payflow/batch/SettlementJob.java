package com.payflow.batch;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Stand-in for the nightly end-of-day batch: every 5 minutes, moves PENDING
 * payments to SETTLED and records the run in SETTLEMENT_BATCHES.
 *
 * Rows with a NULL currency cannot be settled and are counted as skipped —
 * the job still reports SUCCESS (deliberately, for the silent-failure scenario).
 */
@Component
public class SettlementJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong lastRunEpoch = new AtomicLong(0);
    private final AtomicLong lastSkipped = new AtomicLong(0);

    public SettlementJob(JdbcTemplate jdbcTemplate, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        registry.gauge("settlement_last_run_timestamp", lastRunEpoch);
        registry.gauge("settlement_skipped_records", lastSkipped);
    }

    @Scheduled(fixedDelayString = "${payflow.settlement.fixed-delay-ms}",
               initialDelayString = "${payflow.settlement.initial-delay-ms}")
    @Transactional
    public void run() {
        LocalDateTime started = LocalDateTime.now();
        try {
            Integer read = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM payments WHERE status = 'PENDING'", Integer.class);
            int settled = jdbcTemplate.update(
                    "UPDATE payments SET status = 'SETTLED', settled_at = SYSTIMESTAMP "
                    + "WHERE status = 'PENDING' AND currency IS NOT NULL");
            int skipped = (read == null ? 0 : read) - settled;

            jdbcTemplate.update(
                    "INSERT INTO settlement_batches (started_at, finished_at, read_count, "
                    + "settled_count, skipped_count, status) VALUES (?, SYSTIMESTAMP, ?, ?, ?, 'SUCCESS')",
                    Timestamp.valueOf(started), read, settled, skipped);

            lastRunEpoch.set(Instant.now().getEpochSecond());
            lastSkipped.set(skipped);
            log.info("settlement run finished",
                    kv("readCount", read), kv("settledCount", settled),
                    kv("skippedCount", skipped), kv("batchStatus", "SUCCESS"));
        } catch (Exception e) {
            log.error("settlement run failed: {}", e.getMessage(), e);
            throw e;
        }
    }
}
