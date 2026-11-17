package com.khmerdub.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.khmerdub.api.dialogue.DialogueRevision;
import com.khmerdub.api.dialogue.DialogueRevisionRepository;
import com.khmerdub.api.dialogue.DialogueSegment;
import com.khmerdub.api.dialogue.DialogueSegmentRepository;
import com.khmerdub.api.dialogue.Speaker;
import com.khmerdub.api.dialogue.SpeakerRepository;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.storage.StorageService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Shared setup for the M3b-2 review tests: a project with an ACTIVE dialogue revision, plus HTTP helpers. */
abstract class ReviewTestSupport {

    static final long DURATION_MS = 600_000;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectService projects;
    @Autowired MediaAssetRepository assets;
    @Autowired StorageService storage;
    @Autowired JobEngine engine;
    @Autowired DialogueRevisionRepository revisions;
    @Autowired SpeakerRepository speakers;
    @Autowired DialogueSegmentRepository segments;

    EngineFixtures fx;

    @BeforeEach
    void resetDatabase() {
        fx = new EngineFixtures(projects, assets, storage, engine, jdbc);
        fx.reset();
        jdbc.update("DELETE FROM voice_profiles");
    }

    /** A project's dialogue revision: speakers SPK_01, SPK_02 and 4 Chinese lines with word timings. */
    record Dialogue(UUID project, UUID revision, UUID spk1, UUID spk2, List<UUID> lines) {
    }

    Dialogue activeDialogue() throws Exception {
        UUID p = fx.projectWithMovie("movie.mp4");
        jdbc.update("UPDATE dub_projects SET duration_ms = ? WHERE id = ?", DURATION_MS, p);
        return revision(p, 1, DialogueRevision.Status.ACTIVE);
    }

    Dialogue revision(UUID project, int no, DialogueRevision.Status status) {
        DialogueRevision r = new DialogueRevision(project, no, null, "QUALITY", Map.of());
        r.setStatus(status);
        if (status == DialogueRevision.Status.ACTIVE || status == DialogueRevision.Status.SUPERSEDED) {
            r.setActivatedAt(OffsetDateTime.now().minusHours(1)); // a superseded revision was in use before
        }
        if (status == DialogueRevision.Status.SUPERSEDED) {
            r.setSupersededAt(OffsetDateTime.now().minusMinutes(30));
        }
        r = revisions.saveAndFlush(r);
        Speaker s1 = speakers.saveAndFlush(new Speaker(project, r.getId(), "SPK_01", 0, 0, 0));
        Speaker s2 = speakers.saveAndFlush(new Speaker(project, r.getId(), "SPK_02", 0, 0, 1));
        List<UUID> lines = new ArrayList<>();
        lines.add(line(r, s1, 0, List.of(w("你好", 1_000, 1_800), w("我是", 1_800, 3_000))));
        lines.add(line(r, s2, 1, List.of(w("今天", 4_000, 4_600), w("去", 4_600, 5_000), w("哪里", 5_000, 6_000))));
        lines.add(line(r, s1, 2, List.of(w("回家", 7_000, 8_500), w("吧", 8_500, 9_000))));
        lines.add(line(r, s2, 3, List.of(w("碎碎", 10_000, 11_000), w("在哪", 11_000, 12_000))));
        jdbc.update("UPDATE speakers s SET speech_ms = (SELECT coalesce(sum(end_ms - start_ms), 0) FROM dialogue_segments d "
                + "WHERE d.speaker_id = s.id), segment_count = (SELECT count(*) FROM dialogue_segments d "
                + "WHERE d.speaker_id = s.id) WHERE s.revision_id = ?", r.getId());
        Long dirty = jdbc.queryForObject("SELECT count(*) FROM dialogue_segments WHERE revision_id = ? AND version <> 0",
                Long.class, r.getId());
        if (dirty == null || dirty != 0) {
            throw new IllegalStateException("fixture lines must start at version 0");
        }
        return new Dialogue(project, r.getId(), s1.getId(), s2.getId(), lines);
    }

    private UUID line(DialogueRevision r, Speaker s, int seq, List<Map<String, Object>> words) {
        String text = words.stream().map(x -> (String) x.get("w")).reduce("", String::concat);
        long start = ((Number) words.getFirst().get("s")).longValue();
        long end = ((Number) words.getLast().get("e")).longValue();
        return segments.saveAndFlush(new DialogueSegment(r.getProjectId(), r.getId(), s.getId(), seq, start, end, "zh",
                text, 0.9f, words, 1.0f, -0.2f, Map.of())).getId();
    }

    /** Integers, as in real imported dialogue (a Long would read back as Integer and look "changed" to Hibernate). */
    static Map<String, Object> w(String word, int s, int e) {
        return Map.of("w", word, "s", s, "e", e, "p", 0.9);
    }

    // ------------------------------------------------------------------------------------------------- HTTP

    /** Send a request (JSON body optional), assert the status, return the parsed JSON body (or an empty map). */
    Map<String, Object> send(MockHttpServletRequestBuilder req, Object body, int expectedStatus) throws Exception {
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
        String text = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if (res.getStatus() != expectedStatus) {
            throw new AssertionError("expected HTTP " + expectedStatus + " but got " + res.getStatus() + ": " + text);
        }
        return text.isBlank() || !text.trim().startsWith("{") ? Map.of()
                : json.readValue(text, new TypeReference<Map<String, Object>>() { });
    }

    List<Map<String, Object>> sendList(MockHttpServletRequestBuilder req, int expectedStatus) throws Exception {
        MockHttpServletResponse res = mvc.perform(req).andReturn().getResponse();
        String text = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        if (res.getStatus() != expectedStatus) {
            throw new AssertionError("expected HTTP " + expectedStatus + " but got " + res.getStatus() + ": " + text);
        }
        return json.readValue(text, new TypeReference<List<Map<String, Object>>>() { });
    }

    /** Raw response body of a GET (for privacy scans). */
    String raw(String url, Object... vars) throws Exception {
        return mvc.perform(get(url, vars)).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------- DB state

    long lineVersion(UUID id) {
        return jdbc.queryForObject("SELECT version FROM dialogue_segments WHERE id = ?", Long.class, id);
    }

    long speakerVersion(UUID id) {
        return jdbc.queryForObject("SELECT version FROM speakers WHERE id = ?", Long.class, id);
    }

    Map<String, Object> lineRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM dialogue_segments WHERE id = ?", id);
    }

    Map<String, Object> speakerRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM speakers WHERE id = ?", id);
    }

    long operations(UUID revision) {
        return jdbc.queryForObject("SELECT count(*) FROM review_operations WHERE revision_id = ?", Long.class, revision);
    }

    /** Everything the user can see in a revision (no versions or timestamps), for exact before/after comparisons. */
    Map<String, Object> export(UUID revision) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", jdbc.queryForList("""
                SELECT id, speaker_id, sequence_no, start_ms, end_ms, source_language, source_text, source_text_edited,
                       translated_text, edited_text, confidence, review_status, words::text AS words, speaker_assignment,
                       speaker_purity, asr_avg_logprob, flags::text AS flags
                  FROM dialogue_segments WHERE revision_id = ? ORDER BY sequence_no""", revision));
        out.put("speakers", jdbc.queryForList("""
                SELECT id, speaker_code, character_name, description, suggested_category, voice_profile_id,
                       voice_assignment, voice_settings::text AS voice_settings, review_status, origin, merged_into_id,
                       speech_ms, segment_count, display_order
                  FROM speakers WHERE revision_id = ? ORDER BY speaker_code""", revision));
        return out;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    static Map<String, Object> ref(UUID id, long version) {
        return map("id", id, "version", version);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> operation(Map<String, Object> result) {
        return (Map<String, Object>) result.get("operation");
    }
}
