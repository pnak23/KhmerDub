package com.khmerdub.api.power;

import com.khmerdub.api.config.KhmerDubProperties;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.power.KeepAwakePolicy.Decision;
import com.khmerdub.api.power.KeepAwakePolicy.JobSnapshot;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Holds the Windows keep-awake request (through the Windows AI service) only while a heavy job is actively
 * processing, following {@link KeepAwakePolicy}. Renews every renewInterval with a TTL, so if Spring stops, the
 * Windows side releases the request by itself within the TTL. Releases within ~5 s of the decision changing.
 */
@Component
public class PowerKeeper {

    private static final Logger log = LoggerFactory.getLogger(PowerKeeper.class);
    static final String HOLDER = "job-engine";

    private static final String SNAPSHOT_SQL = """
            SELECT j.id, j.status,
                   (SELECT max(e.created_at) FROM job_events e
                     WHERE e.job_id = j.id AND e.event_type IN ('JOB_STARTED', 'JOB_RESUMED')) AS run_started,
                   (SELECT max(e.created_at) FROM job_events e
                     WHERE e.job_id = j.id AND e.event_type IN ('JOB_PAUSING', 'JOB_CANCELLING')) AS status_since,
                   (SELECT max(greatest(a.claimed_at, a.last_heartbeat_at,
                               CASE WHEN a.end_reason IN ('COMPLETED', 'WORKER_ERROR', 'INVALID_INPUT')
                                    THEN a.finished_at END))
                      FROM processing_attempts a JOIN processing_chunks c ON c.id = a.chunk_id
                     WHERE c.job_id = j.id) AS last_signal,
                   (SELECT max(greatest(a.progress_changed_at,
                               CASE WHEN a.end_reason = 'COMPLETED' THEN a.finished_at END))
                      FROM processing_attempts a JOIN processing_chunks c ON c.id = a.chunk_id
                     WHERE c.job_id = j.id) AS last_progress,
                   (SELECT count(*) FROM processing_attempts a JOIN processing_chunks c ON c.id = a.chunk_id
                     WHERE c.job_id = j.id AND a.status IN ('DISPATCHED', 'RUNNING')) AS live
              FROM processing_jobs j
             WHERE j.status IN ('RUNNING', 'PAUSING', 'CANCELLING') AND j.job_type = ANY (?)
             ORDER BY j.created_at
            """;

    private final JdbcTemplate jdbc;
    private final RestClient aiService;
    private final PowerProperties props;
    private final Duration lease;
    private final Clock clock;

    private volatile Boolean holding; // null = unknown (e.g. after a restart): release once to be sure
    private volatile OffsetDateTime lastRenewal;
    private volatile OffsetDateTime lastChange;
    private volatile Decision last = Decision.release("not evaluated yet");
    private volatile String lastError;

    public PowerKeeper(JdbcTemplate jdbc, RestClient aiServiceRestClient, PowerProperties props,
                       KhmerDubProperties khmerdub, Clock clock) {
        this.jdbc = jdbc;
        this.aiService = aiServiceRestClient;
        this.props = props;
        this.lease = khmerdub.jobs().lease();
        this.clock = clock;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    public void tick() {
        if (!props.enabled()) {
            return;
        }
        try {
            evaluate();
        } catch (RuntimeException e) {
            log.warn("Keep-awake evaluation failed: {}", e.toString());
        }
    }

    /** One evaluation step (also called directly by tests). */
    public synchronized Decision evaluate() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Decision d = KeepAwakePolicy.decide(now, snapshot(), lease, props);
        if (d.hold()) {
            boolean due = !Boolean.TRUE.equals(holding) || lastRenewal == null
                    || !lastRenewal.plus(props.renewInterval()).isAfter(now);
            if (due && send("/internal/power/hold", Map.of("holder", HOLDER, "ttlSeconds", props.ttl().toSeconds(),
                    "reason", d.reason()))) {
                if (!Boolean.TRUE.equals(holding)) {
                    log.info("Keep-awake ON: {}", d.reason());
                    lastChange = now;
                }
                holding = true;
                lastRenewal = now;
            }
        } else if (!Boolean.FALSE.equals(holding)) {
            if (send("/internal/power/release", Map.of("holder", HOLDER, "reason", d.reason()))) {
                if (Boolean.TRUE.equals(holding)) {
                    log.info("Keep-awake OFF: {}", d.reason());
                    lastChange = now;
                }
                holding = false;
            }
        }
        last = d;
        return d;
    }

    @PreDestroy
    public void releaseOnShutdown() {
        if (props.enabled() && !Boolean.FALSE.equals(holding)) {
            send("/internal/power/release", Map.of("holder", HOLDER, "reason", "API shutting down"));
            holding = false;
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("holding", holding);
        m.put("reason", last.reason());
        m.put("jobId", last.jobId());
        m.put("lastChange", lastChange);
        m.put("lastRenewal", lastRenewal);
        m.put("lastError", lastError);
        m.put("rules", Map.of("leaseSeconds", lease.toSeconds(), "recoveryMarginSeconds", props.recoveryMargin().toSeconds(),
                "stallLimitMinutes", props.stallLimit().toMinutes(), "pausingMaxMinutes", props.pausingMax().toMinutes(),
                "cancellingMaxMinutes", props.cancellingMax().toMinutes(), "maxHoldHours", props.maxHold().toHours(),
                "renewSeconds", props.renewInterval().toSeconds(), "ttlSeconds", props.ttl().toSeconds()));
        return m;
    }

    public List<JobSnapshot> snapshot() {
        String[] heavy = Arrays.stream(JobType.values()).filter(JobType::heavy).map(Enum::name).toArray(String[]::new);
        return jdbc.query(con -> {
            var ps = con.prepareStatement(SNAPSHOT_SQL);
            ps.setArray(1, con.createArrayOf("varchar", heavy));
            return ps;
        }, (rs, i) -> new JobSnapshot(rs.getObject("id", UUID.class), rs.getString("status"), time(rs.getTimestamp("run_started")),
                time(rs.getTimestamp("status_since")), time(rs.getTimestamp("last_signal")),
                time(rs.getTimestamp("last_progress")), rs.getInt("live")));
    }

    private boolean send(String path, Map<String, Object> body) {
        try {
            aiService.post().uri(path).body(body).retrieve().toBodilessEntity();
            lastError = null;
            return true;
        } catch (RestClientException e) {
            lastError = path + ": " + e.getClass().getSimpleName();
            log.warn("Keep-awake call {} failed: {}", path, e.getClass().getSimpleName());
            return false;
        }
    }

    private static OffsetDateTime time(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
