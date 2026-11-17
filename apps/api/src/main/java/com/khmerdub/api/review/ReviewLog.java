package com.khmerdub.api.review;

import com.khmerdub.api.review.ReviewStore.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The append-only review_operations log: one entry per review change, with before/after snapshots of every row the
 * change touched. Undo is strictly last-change-first and only while every recorded row still has the version the
 * change left behind. The payload holds dialogue text and speaker settings only; never embeddings or file paths.
 */
@Component
public class ReviewLog {

    public static final String FORMAT = "khmerdub.review-op.v1";

    private final JdbcTemplate jdbc;
    private final ReviewStore store;
    private final ObjectMapper json;

    public ReviewLog(JdbcTemplate jdbc, ReviewStore store, ObjectMapper json) {
        this.jdbc = jdbc;
        this.store = store;
        this.json = json;
    }

    /** Collects what one operation touches, in order (undo replays it backwards). */
    public static final class Recorder {
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final List<Map<String, Object>> renumbers = new ArrayList<>();
        private final List<UUID> segmentIds = new ArrayList<>();
        private final List<UUID> speakerIds = new ArrayList<>();

        /** A row about to change (its current content is the "before" snapshot). Recording twice keeps the first. */
        public void updating(Table t, Map<String, Object> before) {
            track(t, UUID.fromString((String) before.get("id")), before, "UPDATED");
        }

        public void created(Table t, UUID id) {
            track(t, id, null, "CREATED");
        }

        public void deleting(Table t, Map<String, Object> before) {
            String key = t.sqlName() + ":" + before.get("id");
            rows.removeIf(r -> key.equals(r.get("key")));
            seen.remove(key);
            track(t, UUID.fromString((String) before.get("id")), before, "DELETED");
        }

        public void renumbered(UUID revisionId, int aboveSeq, int delta) {
            renumbers.add(Map.of("revisionId", revisionId.toString(), "aboveSeq", aboveSeq, "delta", delta));
        }

        private void track(Table t, UUID id, Map<String, Object> before, String kind) {
            String key = t.sqlName() + ":" + id;
            if (!seen.add(key)) {
                return;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", key);
            r.put("table", t.sqlName());
            r.put("id", id.toString());
            r.put("kind", kind);
            r.put("before", before);
            rows.add(r);
            (t == Table.SEGMENTS ? segmentIds : speakerIds).add(id);
        }

        public List<UUID> segmentIds() {
            return segmentIds;
        }

        public List<UUID> speakerIds() {
            return speakerIds;
        }
    }

    public record Logged(UUID id, long seq, String type) {
    }

    /** Snapshot the "after" state of every recorded row and append the operation. */
    public Logged record(UUID projectId, UUID revisionId, String type, String summary, Recorder rec) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : rec.rows) {
            Map<String, Object> out = new LinkedHashMap<>(r);
            out.remove("key");
            boolean deleted = "DELETED".equals(r.get("kind"));
            out.put("after", deleted ? null : store.read(Table.of((String) r.get("table")), UUID.fromString((String) r.get("id"))));
            rows.add(out);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("format", FORMAT);
        payload.put("summary", summary);
        payload.put("rows", rows);
        payload.put("renumbers", rec.renumbers);
        Map<String, Object> created = jdbc.queryForMap("INSERT INTO review_operations (project_id, revision_id, op_type, "
                        + "payload) VALUES (?, ?, ?, ?::jsonb) RETURNING id, seq", projectId, revisionId, type,
                json.writeValueAsString(payload));
        return new Logged((UUID) created.get("id"), ((Number) created.get("seq")).longValue(), type);
    }

    public record Entry(UUID id, UUID revisionId, long seq, String type, String summary, OffsetDateTime createdAt,
                        OffsetDateTime undoneAt) {
    }

    public List<Entry> list(UUID projectId, UUID revisionId, int limit) {
        return jdbc.query("""
                SELECT id, revision_id, seq, op_type, payload->>'summary' AS summary, created_at, undone_at
                  FROM review_operations WHERE project_id = ? AND revision_id = ? ORDER BY seq DESC LIMIT ?""",
                (rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("revision_id", UUID.class),
                        rs.getLong("seq"), rs.getString("op_type"), rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("undone_at", OffsetDateTime.class)),
                projectId, revisionId, limit);
    }

    /** One history entry of the project, if it exists. */
    public Entry entry(UUID projectId, UUID id) {
        List<Entry> rows = jdbc.query("""
                SELECT id, revision_id, seq, op_type, payload->>'summary' AS summary, created_at, undone_at
                  FROM review_operations WHERE project_id = ? AND id = ?""",
                (rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("revision_id", UUID.class),
                        rs.getLong("seq"), rs.getString("op_type"), rs.getString("summary"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("undone_at", OffsetDateTime.class)),
                projectId, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** The most recent change of the revision that has not been undone (plain read, no lock), if any. */
    public UUID newestNotUndone(UUID revisionId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM review_operations WHERE revision_id = ? AND undone_at IS NULL "
                + "ORDER BY seq DESC LIMIT 1", UUID.class, revisionId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /** The most recent change of the revision that has not been undone (locked), if any. */
    public UUID latestUndoable(UUID revisionId) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM review_operations WHERE revision_id = ? AND undone_at IS NULL "
                + "ORDER BY seq DESC LIMIT 1 FOR UPDATE", UUID.class, revisionId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    public record Stored(UUID id, UUID projectId, UUID revisionId, long seq, String type, OffsetDateTime createdAt,
                         boolean undone, Map<String, Object> payload) {
    }

    public Stored lock(UUID operationId) {
        List<Stored> rows = jdbc.query("SELECT id, project_id, revision_id, seq, op_type, created_at, undone_at, "
                        + "payload::text AS payload FROM review_operations WHERE id = ? FOR UPDATE",
                (rs, i) -> new Stored(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("revision_id", UUID.class), rs.getLong("seq"), rs.getString("op_type"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("undone_at") != null,
                        json.readValue(rs.getString("payload"), new TypeReference<Map<String, Object>>() { })),
                operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * Mark undone and keep the version equivalences the undo created: each entry says "row (table, id) now has version
     * {@code version}, whose content equals its earlier version {@code equivalent}". Versions themselves only ever grow.
     */
    public void markUndone(UUID operationId, List<Map<String, Object>> versions) {
        Map<String, Object> undo = new LinkedHashMap<>();
        undo.put("versions", versions);
        jdbc.update("UPDATE review_operations SET undone_at = clock_timestamp(), payload = payload || ?::jsonb WHERE id = ?",
                json.writeValueAsString(Map.of("undo", undo)), operationId);
    }

    /**
     * Version equivalences recorded by undos of this revision's changes newer than {@code afterSeq}:
     * row key ("table:id") -> (restored version -> earlier version with identical content).
     */
    public Map<String, Map<Long, Long>> equivalences(UUID revisionId, long afterSeq) {
        Map<String, Map<Long, Long>> out = new java.util.HashMap<>();
        jdbc.query("SELECT payload -> 'undo' -> 'versions' AS v FROM review_operations "
                + "WHERE revision_id = ? AND seq > ? AND undone_at IS NOT NULL", rs -> {
            String text = rs.getString("v");
            if (text == null) {
                return;
            }
            List<Map<String, Object>> list = json.readValue(text, new TypeReference<List<Map<String, Object>>>() { });
            for (Map<String, Object> e : list) {
                out.computeIfAbsent(e.get("table") + ":" + e.get("id"), k -> new java.util.HashMap<>())
                        .put(((Number) e.get("version")).longValue(), ((Number) e.get("equivalent")).longValue());
            }
        }, revisionId, afterSeq);
        return out;
    }

    public long countActive(UUID revisionId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM review_operations WHERE revision_id = ? AND undone_at IS NULL",
                Long.class, revisionId);
        return n == null ? 0 : n;
    }
}
