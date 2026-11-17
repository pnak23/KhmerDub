package com.khmerdub.api.review;

import com.khmerdub.api.error.ApiException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Row-level access for review operations. Plain SQL on purpose: every write carries an explicit version check (a
 * conflicting edit is rejected, never overwritten), and every operation can record exact before/after snapshots of
 * the rows it touched, which is what makes undo exact. Snapshot values are JSON-friendly: uuid as text, jsonb as
 * lists/maps, numbers as numbers. Must run inside a transaction.
 */
@Component
public class ReviewStore {

    public enum Table {
        SEGMENTS("dialogue_segments",
                List.of("id", "project_id", "revision_id", "speaker_id", "sequence_no", "start_ms", "end_ms",
                        "source_language", "source_text", "source_text_edited", "translated_text", "edited_text",
                        "translation_model", "emotion", "confidence", "review_status", "words", "speaker_assignment",
                        "speaker_purity", "asr_avg_logprob", "flags", "version"),
                // what undo puts back on a row that still exists (sequence numbers move only through renumbering)
                List.of("speaker_id", "start_ms", "end_ms", "source_text", "source_text_edited", "translated_text",
                        "edited_text", "confidence", "review_status", "words", "speaker_assignment", "speaker_purity",
                        "asr_avg_logprob", "flags")),
        SPEAKERS("speakers",
                List.of("id", "project_id", "revision_id", "speaker_code", "character_name", "description",
                        "suggested_category", "voice_profile_id", "voice_assignment", "voice_settings", "review_status",
                        "origin", "merged_into_id", "speech_ms", "segment_count", "display_order", "version"),
                // speech_ms / segment_count are derived and recounted instead
                List.of("character_name", "description", "suggested_category", "voice_profile_id", "voice_assignment",
                        "voice_settings", "review_status", "origin", "merged_into_id", "display_order"));

        final String sql;
        final List<String> columns;
        final List<String> restorable;

        Table(String sql, List<String> columns, List<String> restorable) {
            this.sql = sql;
            this.columns = columns;
            this.restorable = restorable;
        }

        public static Table of(String name) {
            for (Table t : values()) {
                if (t.sql.equals(name)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("unknown table " + name);
        }

        public String sqlName() {
            return sql;
        }
    }

    static final Set<String> UUID_COLUMNS = Set.of("id", "project_id", "revision_id", "speaker_id", "voice_profile_id",
            "merged_into_id");
    static final Set<String> JSON_COLUMNS = Set.of("words", "flags", "voice_settings");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReviewStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One row, locked for the rest of the transaction; null when it does not exist. */
    public Map<String, Object> lock(Table t, UUID id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT " + String.join(", ", t.columns) + " FROM " + t.sql
                + " WHERE id = ? FOR UPDATE", (rs, i) -> row(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Several rows locked in id order (a fixed order, so two operations can never deadlock). */
    public Map<UUID, Map<String, Object>> lockAll(Table t, Collection<UUID> ids) {
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String[] array = ids.stream().map(UUID::toString).distinct().toArray(String[]::new);
        jdbc.query("SELECT " + String.join(", ", t.columns) + " FROM " + t.sql
                        + " WHERE id = ANY(?::uuid[]) ORDER BY id FOR UPDATE",
                rs -> {
                    Map<String, Object> r = row(rs);
                    out.put(UUID.fromString((String) r.get("id")), r);
                }, (Object) array);
        return out;
    }

    /** Ids of the rows matching a fixed query, locked in id order. */
    public List<UUID> lockIds(Table t, String where, Object... args) {
        return jdbc.queryForList("SELECT id FROM " + t.sql + " WHERE " + where + " ORDER BY id FOR UPDATE", UUID.class, args);
    }

    public Map<String, Object> read(Table t, UUID id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT " + String.join(", ", t.columns) + " FROM " + t.sql
                + " WHERE id = ?", (rs, i) -> row(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Update the given columns if the row still has the expected version; bumps the version. */
    public void update(Table t, UUID id, long expectedVersion, Map<String, Object> changes) {
        if (changes.isEmpty()) {
            return;
        }
        List<Object> args = new ArrayList<>();
        String set = changes.entrySet().stream().map(e -> {
            requireColumn(t, e.getKey());
            args.add(bind(e.getKey(), e.getValue()));
            return e.getKey() + " = " + placeholder(e.getKey());
        }).collect(Collectors.joining(", "));
        args.add(id);
        args.add(expectedVersion);
        int n = jdbc.update("UPDATE " + t.sql + " SET " + set + ", version = version + 1 WHERE id = ? AND version = ?",
                args.toArray());
        if (n != 1) {
            throw conflict();
        }
    }

    /**
     * Undo: put columns back. The version still moves FORWARD (never back to an earlier number), so a request based on
     * any version seen before the undo is refused (no ABA). Returns the new version.
     */
    public long restore(Table t, UUID id, long expectedVersion, Map<String, Object> values) {
        List<Object> args = new ArrayList<>();
        String set = values.entrySet().stream().map(e -> {
            requireColumn(t, e.getKey());
            args.add(bind(e.getKey(), e.getValue()));
            return e.getKey() + " = " + placeholder(e.getKey());
        }).collect(Collectors.joining(", "));
        args.add(id);
        args.add(expectedVersion);
        List<Long> v = jdbc.queryForList("UPDATE " + t.sql + " SET " + set + (set.isEmpty() ? "" : ", ")
                + "version = version + 1 WHERE id = ? AND version = ? RETURNING version", Long.class, args.toArray());
        if (v.size() != 1) {
            throw conflict();
        }
        return v.getFirst();
    }

    /** Insert a row; values must cover the NOT NULL columns. Returns the id (generated unless given). */
    public UUID insert(Table t, Map<String, Object> values) {
        List<String> cols = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : values.entrySet()) {
            requireColumn(t, e.getKey());
            cols.add(e.getKey());
            args.add(bind(e.getKey(), e.getValue()));
        }
        String ph = cols.stream().map(ReviewStore::placeholder).collect(Collectors.joining(", "));
        return jdbc.queryForObject("INSERT INTO " + t.sql + " (" + String.join(", ", cols) + ") VALUES (" + ph
                + ") RETURNING id", UUID.class, args.toArray());
    }

    public void delete(Table t, UUID id, long expectedVersion) {
        if (jdbc.update("DELETE FROM " + t.sql + " WHERE id = ? AND version = ?", id, expectedVersion) != 1) {
            throw conflict();
        }
    }

    /** Lines numbered above {@code aboveSeq} move by {@code delta} (no version change: numbering is derived). */
    public void renumber(UUID revisionId, int aboveSeq, int delta) {
        jdbc.update("UPDATE dialogue_segments SET sequence_no = sequence_no + ? WHERE revision_id = ? AND sequence_no > ?",
                delta, revisionId, aboveSeq);
    }

    /** Check the one-number-per-line rule at commit instead of after every statement. */
    public void deferSequenceCheck() {
        jdbc.execute("SET CONSTRAINTS uq_dialogue_segments_revision_seq DEFERRED");
    }

    /** speech_ms / segment_count are derived from the lines; recompute them for a whole revision. */
    public void recountSpeakers(UUID revisionId) {
        jdbc.update("""
                UPDATE speakers s
                   SET speech_ms = coalesce((SELECT sum(d.end_ms - d.start_ms) FROM dialogue_segments d
                                              WHERE d.speaker_id = s.id), 0),
                       segment_count = (SELECT count(*) FROM dialogue_segments d WHERE d.speaker_id = s.id)
                 WHERE s.revision_id = ?""", revisionId);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    public static long version(Map<String, Object> row) {
        return ((Number) row.get("version")).longValue();
    }

    public static UUID uuid(Map<String, Object> row, String column) {
        Object v = row.get(column);
        return v == null ? null : UUID.fromString(v.toString());
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> words(Map<String, Object> segment) {
        Object v = segment.get("words");
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    public static ApiException conflict() {
        return ApiException.conflict("EDIT_CONFLICT",
                "This was changed by another edit in the meantime. Reload and try again; nothing was changed.");
    }

    private static void requireColumn(Table t, String column) {
        if (!t.columns.contains(column)) {
            throw new IllegalArgumentException("column " + column + " is not part of " + t.sql);
        }
    }

    private static String placeholder(String column) {
        return UUID_COLUMNS.contains(column) ? "?::uuid" : JSON_COLUMNS.contains(column) ? "?::jsonb" : "?";
    }

    private Object bind(String column, Object value) {
        if (value == null) {
            return null;
        }
        if (JSON_COLUMNS.contains(column)) {
            return json.writeValueAsString(value);
        }
        if (UUID_COLUMNS.contains(column)) {
            return value.toString();
        }
        return value;
    }

    private Map<String, Object> row(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            String col = md.getColumnName(i);
            Object v;
            if ("jsonb".equalsIgnoreCase(md.getColumnTypeName(i)) || "json".equalsIgnoreCase(md.getColumnTypeName(i))) {
                String text = rs.getString(i);
                v = text == null ? null : json.readValue(text, new TypeReference<Object>() { });
            } else {
                v = rs.getObject(i);
            }
            if (v instanceof UUID u) {
                v = u.toString();
            } else if (v instanceof Float f) {
                v = f.doubleValue();
            }
            r.put(col, v);
        }
        return r;
    }
}
