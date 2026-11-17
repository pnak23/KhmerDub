package com.khmerdub.api.review;

import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.review.ReviewLog.Entry;
import com.khmerdub.api.review.ReviewViews.OperationView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only review queries (no locks). */
@Service
public class ReviewQueries {

    private final JdbcTemplate jdbc;
    private final ReviewLog log;

    public ReviewQueries(JdbcTemplate jdbc, ReviewLog log) {
        this.jdbc = jdbc;
        this.log = log;
    }

    @Transactional(readOnly = true)
    public List<OperationView> operations(UUID projectId, UUID revisionId, int limit) {
        if (jdbc.queryForList("SELECT 1 FROM dub_projects WHERE id = ?", Integer.class, projectId).isEmpty()) {
            throw ApiException.notFound("Project");
        }
        List<Map<String, Object>> rev = revisionId == null
                ? jdbc.queryForList("SELECT id, status FROM dialogue_revisions WHERE project_id = ? AND status = 'ACTIVE'",
                projectId)
                : jdbc.queryForList("SELECT id, status FROM dialogue_revisions WHERE project_id = ? AND id = ?",
                projectId, revisionId);
        if (rev.isEmpty()) {
            if (revisionId != null) {
                throw ApiException.notFound("Revision");
            }
            return List.of();
        }
        UUID id = (UUID) rev.getFirst().get("id");
        boolean active = "ACTIVE".equals(rev.getFirst().get("status"));
        List<Entry> entries = new ArrayList<>(log.list(projectId, id, Math.max(1, Math.min(limit, 1000))));
        // The change Undo would act on is always included, even when it is older than the entries asked for
        // (e.g. after undoing many changes), so the client can always see and undo it.
        UUID newest = log.newestNotUndone(id);
        if (newest != null && entries.stream().noneMatch(e -> e.id().equals(newest))) {
            Entry e = log.entry(projectId, newest);
            if (e != null) {
                entries.add(e); // older than every listed entry, so the list stays newest-first
            }
        }
        return entries.stream().map(e -> new OperationView(e.id(), e.seq(), e.type(), e.summary(), e.createdAt(),
                e.undoneAt(), active && e.id().equals(newest))).toList();
    }
}
