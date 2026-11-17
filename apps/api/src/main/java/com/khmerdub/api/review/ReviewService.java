package com.khmerdub.api.review;

import static com.khmerdub.api.review.ReviewStore.Table.SEGMENTS;
import static com.khmerdub.api.review.ReviewStore.Table.SPEAKERS;
import static com.khmerdub.api.review.ReviewStore.uuid;
import static com.khmerdub.api.review.ReviewStore.version;

import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.review.ReviewLog.Logged;
import com.khmerdub.api.review.ReviewLog.Recorder;
import com.khmerdub.api.review.ReviewLog.Stored;
import com.khmerdub.api.review.ReviewStore.Table;
import com.khmerdub.api.review.ReviewViews.CreateSpeakerRequest;
import com.khmerdub.api.review.ReviewViews.EditSegmentRequest;
import com.khmerdub.api.review.ReviewViews.MergeSegmentsRequest;
import com.khmerdub.api.review.ReviewViews.MergeSpeakersRequest;
import com.khmerdub.api.review.ReviewViews.OperationView;
import com.khmerdub.api.review.ReviewViews.ReassignRequest;
import com.khmerdub.api.review.ReviewViews.SplitPart;
import com.khmerdub.api.review.ReviewViews.SplitSegmentRequest;
import com.khmerdub.api.review.ReviewViews.SplitSpeakerRequest;
import com.khmerdub.api.review.ReviewViews.UpdateSpeakerRequest;
import com.khmerdub.api.review.ReviewViews.VersionRef;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Review changes to the ACTIVE dialogue revision: line edits, split/merge, speaker reassignment, speaker
 * create/update/merge/split, and last-change-first undo.
 *
 * <p>Safety rules (each enforced here, each covered by tests):
 * <ul>
 *   <li>Only the ACTIVE revision can change; older and newly processed revisions are read-only. The project row is
 *       locked first, so a change can never interleave with switching revisions.</li>
 *   <li>The recognised text (source_text) is never edited. Split and merge only redistribute it at recognised word
 *       boundaries (the parts always join back to the original); your corrections and Khmer have their own fields.</li>
 *   <li>Every request names the version of every row it was based on; a different current version is a conflict
 *       (409), never a silent overwrite.</li>
 *   <li>Each change is one transaction and one review_operations entry with before/after snapshots. Merged speakers
 *       are kept (merged_into_id), so ids never disappear.</li>
 *   <li>Undo is last-change-first and refused if anything it would restore was changed since.</li>
 * </ul>
 */
@Service
public class ReviewService {

    /** The revision being edited (the project row is locked for the rest of the transaction). */
    public record EditContext(UUID projectId, UUID revisionId, Long durationMs) {
    }

    /** What a change did; the controller turns it into current views. */
    public record Done(OperationView operation, List<UUID> segmentIds, List<UUID> speakerIds, List<UUID> removedSegmentIds,
                       List<UUID> removedSpeakerIds) {
    }

    static final long MAX_LINE_MS = 10 * 60_000;
    static final Set<String> NO_SPACE_LANGUAGES = Set.of("zh", "ja", "th", "lo", "km", "my", "bo");
    private static final Pattern CODE = Pattern.compile("^SPK_(\\d{1,6})$");

    private final JdbcTemplate jdbc;
    private final ReviewStore store;
    private final ReviewLog log;
    private final Clock clock;

    public ReviewService(JdbcTemplate jdbc, ReviewStore store, ReviewLog log, Clock clock) {
        this.jdbc = jdbc;
        this.store = store;
        this.log = log;
        this.clock = clock;
    }

    // ===================================================================================================== context

    /** Lock the project and find its ACTIVE revision; refuses archived projects and projects without dialogue. */
    public EditContext editable(UUID projectId) {
        List<Map<String, Object>> p = jdbc.queryForList(
                "SELECT archived_at, duration_ms FROM dub_projects WHERE id = ? FOR UPDATE", projectId);
        if (p.isEmpty()) {
            throw ApiException.notFound("Project");
        }
        if (p.getFirst().get("archived_at") != null) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "The project is archived. Restore it first.");
        }
        List<UUID> active = jdbc.queryForList(
                "SELECT id FROM dialogue_revisions WHERE project_id = ? AND status = 'ACTIVE'", UUID.class, projectId);
        if (active.isEmpty()) {
            throw ApiException.conflict("NO_ACTIVE_DIALOGUE", "This project has no active dialogue to edit.");
        }
        Number d = (Number) p.getFirst().get("duration_ms");
        return new EditContext(projectId, active.getFirst(), d == null ? null : d.longValue());
    }

    static ApiException readOnly() {
        return ApiException.conflict("REVISION_READ_ONLY", "Only the active dialogue revision can be edited. Older "
                + "and newly processed revisions are read-only (activate one to work on it).");
    }

    /** The row must belong to the project (else 404) and to the edited revision (else read-only). */
    public static Map<String, Object> own(EditContext c, Map<String, Object> row, String what) {
        if (row == null || !c.projectId().toString().equals(row.get("project_id"))) {
            throw ApiException.notFound(what);
        }
        if (!c.revisionId().toString().equals(row.get("revision_id"))) {
            throw readOnly();
        }
        return row;
    }

    public static void expect(Map<String, Object> row, Long version) {
        if (version == null || version(row) != version) {
            throw ReviewStore.conflict();
        }
    }

    /** A speaker of the edited revision that can receive lines (not merged away). Locked. */
    private Map<String, Object> usableSpeaker(EditContext c, UUID speakerId) {
        Map<String, Object> s = own(c, store.lock(SPEAKERS, speakerId), "Speaker");
        if (s.get("merged_into_id") != null) {
            throw ApiException.conflict("SPEAKER_MERGED", "Speaker %s was merged into another speaker; use that one."
                    .formatted(s.get("speaker_code")));
        }
        return s;
    }

    // ===================================================================================================== lines

    @Transactional
    public Done editSegment(UUID projectId, UUID segmentId, EditSegmentRequest r) {
        EditContext c = editable(projectId);
        Map<String, Object> seg = own(c, store.lock(SEGMENTS, segmentId), "Line");
        expect(seg, r.version());
        Map<String, Object> changes = new LinkedHashMap<>();
        List<String> what = new ArrayList<>();
        if (r.sourceTextEdited() != null) {
            String v = userText(r.sourceTextEdited());
            if (v != null && v.equals(seg.get("source_text"))) {
                v = null; // identical to the recognised text = no correction
            }
            if (!Objects.equals(v, seg.get("source_text_edited"))) {
                changes.put("source_text_edited", v);
                what.add("Chinese");
            }
        }
        if (r.editedText() != null) {
            String v = userText(r.editedText());
            if (!Objects.equals(v, seg.get("edited_text"))) {
                changes.put("edited_text", v);
                what.add("Khmer");
            }
        }
        if (r.reviewStatus() != null && !r.reviewStatus().equals(seg.get("review_status"))) {
            changes.put("review_status", r.reviewStatus());
            what.add("status " + r.reviewStatus());
        }
        UUID oldSpeaker = uuid(seg, "speaker_id");
        if (r.speakerId() != null && !r.speakerId().equals(oldSpeaker)) {
            usableSpeaker(c, r.speakerId());
            changes.put("speaker_id", r.speakerId());
            changes.put("speaker_assignment", "MANUAL");
            what.add("speaker");
        }
        if (r.startMs() != null || r.endMs() != null) {
            long start = r.startMs() != null ? r.startMs() : num(seg, "start_ms");
            long end = r.endMs() != null ? r.endMs() : num(seg, "end_ms");
            checkTiming(c, seg, start, end);
            if (start != num(seg, "start_ms")) {
                changes.put("start_ms", start);
            }
            if (end != num(seg, "end_ms")) {
                changes.put("end_ms", end);
            }
            if (changes.containsKey("start_ms") || changes.containsKey("end_ms")) {
                what.add("timing");
            }
        }
        if (changes.isEmpty()) {
            throw ApiException.badRequest("NOTHING_TO_CHANGE", "The request does not change anything.");
        }
        Recorder rec = new Recorder();
        rec.updating(SEGMENTS, seg);
        store.update(SEGMENTS, segmentId, r.version(), changes);
        Set<UUID> speakers = new LinkedHashSet<>();
        if (changes.containsKey("speaker_id") || changes.containsKey("start_ms") || changes.containsKey("end_ms")) {
            store.recountSpeakers(c.revisionId());
            addNonNull(speakers, oldSpeaker, (UUID) changes.get("speaker_id"));
        }
        String type = what.size() == 1 && what.contains("speaker") ? "REASSIGN_SEGMENT" : "EDIT_SEGMENT";
        String summary = "Line %d: %s".formatted(seq(seg) + 1, String.join(", ", what));
        return done(c, type, summary, rec, List.copyOf(speakers), List.of(), List.of());
    }

    /** Line times stay inside the movie, keep dialogue order, and stay under 10 minutes. */
    private void checkTiming(EditContext c, Map<String, Object> seg, long start, long end) {
        if (start < 0 || end <= start) {
            throw ApiException.badRequest("INVALID_TIMING", "The end of a line must be after its start.");
        }
        if (end - start > MAX_LINE_MS) {
            throw ApiException.badRequest("INVALID_TIMING", "A line can be at most 10 minutes long.");
        }
        if (c.durationMs() != null && end > c.durationMs()) {
            throw ApiException.badRequest("INVALID_TIMING", "A line cannot end after the movie (%d ms)."
                    .formatted(c.durationMs()));
        }
        int seq = seq(seg);
        Long prev = neighbourStart(c, seq - 1);
        Long next = neighbourStart(c, seq + 1);
        if ((prev != null && start < prev) || (next != null && start > next)) {
            throw ApiException.badRequest("TIMING_ORDER", "The new start would move this line past a neighbouring "
                    + "line. Lines keep their order; split or merge lines instead.");
        }
    }

    private Long neighbourStart(EditContext c, int seq) {
        if (seq < 0) {
            return null;
        }
        List<Long> v = jdbc.queryForList("SELECT start_ms FROM dialogue_segments WHERE revision_id = ? AND sequence_no = ?",
                Long.class, c.revisionId(), seq);
        return v.isEmpty() ? null : v.getFirst();
    }

    @Transactional
    public Done splitSegment(UUID projectId, UUID segmentId, SplitSegmentRequest r) {
        EditContext c = editable(projectId);
        Map<String, Object> seg = own(c, store.lock(SEGMENTS, segmentId), "Line");
        expect(seg, r.version());
        List<Map<String, Object>> words = ReviewStore.words(seg);
        int n = words.size();
        if (n < 2) {
            throw ApiException.conflict("SPLIT_NOT_POSSIBLE", "This line has a single recognised word and cannot be "
                    + "split; adjust its timing instead.");
        }
        if (r.atWord() < 1 || r.atWord() >= n) {
            throw ApiException.badRequest("INVALID_SPLIT", "atWord must be between 1 and %d.".formatted(n - 1));
        }
        if (seg.get("translated_text") != null) {
            throw ApiException.conflict("SPLIT_HAS_TRANSLATION", "This line already has an AI translation; it cannot "
                    + "be split safely.");
        }
        if (!join(words).equals(seg.get("source_text"))) {
            throw ApiException.conflict("SPLIT_TEXT_MISMATCH", "The recognised text of this line does not match its "
                    + "word timings, so it cannot be split safely.");
        }
        boolean ownText = seg.get("source_text_edited") != null || seg.get("edited_text") != null;
        if (ownText && (r.first() == null || r.second() == null)) {
            throw ApiException.conflict("SPLIT_NEEDS_TEXT", "This line has your own Chinese or Khmer text. Say what "
                    + "each part keeps (first and second), so nothing is lost.");
        }
        List<Map<String, Object>> a = new ArrayList<>(words.subList(0, r.atWord()));
        List<Map<String, Object>> b = new ArrayList<>(words.subList(r.atWord(), n));
        long aStart = num(seg, "start_ms");
        long bEnd = num(seg, "end_ms");
        long bStart = Math.max(aStart + 1, wordMs(b.getFirst(), "s"));
        long aEnd = Math.min(Math.max(aStart + 1, wordMs(a.getLast(), "e")), bStart);
        if (aEnd <= aStart || bEnd <= bStart) {
            throw ApiException.conflict("SPLIT_TIMING", "The word timings do not allow a split at this word.");
        }
        String status = "APPROVED".equals(seg.get("review_status")) ? "PENDING" : (String) seg.get("review_status");
        int seq = seq(seg);

        Recorder rec = new Recorder();
        rec.updating(SEGMENTS, seg);
        store.deferSequenceCheck();
        store.renumber(c.revisionId(), seq, 1);
        rec.renumbered(c.revisionId(), seq, 1);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("end_ms", aEnd);
        first.put("source_text", join(a));
        first.put("words", a);
        first.put("source_text_edited", part(r.first(), SplitPart::sourceTextEdited));
        first.put("edited_text", part(r.first(), SplitPart::editedText));
        first.put("review_status", status);
        store.update(SEGMENTS, segmentId, r.version(), first);
        Map<String, Object> second = new LinkedHashMap<>();
        for (String col : List.of("project_id", "revision_id", "speaker_id", "source_language", "confidence",
                "speaker_assignment", "speaker_purity", "asr_avg_logprob", "flags")) {
            second.put(col, seg.get(col));
        }
        second.put("sequence_no", seq + 1);
        second.put("start_ms", bStart);
        second.put("end_ms", bEnd);
        second.put("source_text", join(b));
        second.put("words", b);
        second.put("source_text_edited", part(r.second(), SplitPart::sourceTextEdited));
        second.put("edited_text", part(r.second(), SplitPart::editedText));
        second.put("review_status", status);
        UUID newId = store.insert(SEGMENTS, second);
        rec.created(SEGMENTS, newId);
        store.recountSpeakers(c.revisionId());
        List<UUID> speakers = uuid(seg, "speaker_id") == null ? List.of() : List.of(uuid(seg, "speaker_id"));
        return done(c, "SPLIT_SEGMENT", "Split line %d before word %d".formatted(seq + 1, r.atWord() + 1), rec,
                speakers, List.of(), List.of());
    }

    @Transactional
    public Done mergeSegments(UUID projectId, MergeSegmentsRequest r) {
        EditContext c = editable(projectId);
        if (r.first().id().equals(r.second().id())) {
            throw ApiException.badRequest("INVALID_MERGE", "Choose two different lines.");
        }
        Map<UUID, Map<String, Object>> rows = store.lockAll(SEGMENTS, List.of(r.first().id(), r.second().id()));
        Map<String, Object> a = own(c, rows.get(r.first().id()), "Line");
        Map<String, Object> b = own(c, rows.get(r.second().id()), "Line");
        expect(a, r.first().version());
        expect(b, r.second().version());
        if (seq(b) != seq(a) + 1) {
            throw ApiException.conflict("NOT_ADJACENT", "Only neighbouring lines can be merged, the first one followed "
                    + "directly by the second.");
        }
        if (a.get("translated_text") != null || b.get("translated_text") != null) {
            throw ApiException.conflict("MERGE_HAS_TRANSLATION", "These lines already have AI translations; they "
                    + "cannot be merged safely.");
        }
        UUID spkA = uuid(a, "speaker_id");
        UUID spkB = uuid(b, "speaker_id");
        UUID speaker;
        String assignment;
        if (Objects.equals(spkA, spkB)) {
            speaker = spkA;
            assignment = "MANUAL".equals(a.get("speaker_assignment")) || "MANUAL".equals(b.get("speaker_assignment"))
                    ? "MANUAL" : "AUTO";
        } else {
            if (r.speakerId() == null) {
                throw ApiException.conflict("MERGE_SPEAKER_REQUIRED", "The two lines have different speakers; choose "
                        + "which speaker the merged line belongs to.");
            }
            usableSpeaker(c, r.speakerId());
            speaker = r.speakerId();
            assignment = "MANUAL";
        }
        String sep = separator((String) a.get("source_language"), (String) a.get("source_text"),
                (String) b.get("source_text"));
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("speaker_id", speaker);
        merged.put("speaker_assignment", assignment);
        merged.put("end_ms", Math.max(num(a, "end_ms"), num(b, "end_ms")));
        merged.put("source_text", a.get("source_text") + sep + b.get("source_text"));
        List<Map<String, Object>> words = new ArrayList<>(ReviewStore.words(a));
        words.addAll(ReviewStore.words(b));
        merged.put("words", words);
        merged.put("source_text_edited", a.get("source_text_edited") == null && b.get("source_text_edited") == null ? null
                : orElse(a, "source_text_edited", "source_text") + sep + orElse(b, "source_text_edited", "source_text"));
        merged.put("edited_text", joinNonBlank((String) a.get("edited_text"), (String) b.get("edited_text")));
        merged.put("confidence", min(a.get("confidence"), b.get("confidence")));
        merged.put("speaker_purity", min(a.get("speaker_purity"), b.get("speaker_purity")));
        merged.put("asr_avg_logprob", min(a.get("asr_avg_logprob"), b.get("asr_avg_logprob")));
        merged.put("flags", unionFlags(a.get("flags"), b.get("flags")));
        boolean attention = Set.of("NEEDS_REVIEW", "REJECTED").contains(a.get("review_status"))
                || Set.of("NEEDS_REVIEW", "REJECTED").contains(b.get("review_status"));
        merged.put("review_status", attention ? "NEEDS_REVIEW" : "PENDING");

        Recorder rec = new Recorder();
        rec.updating(SEGMENTS, a);
        rec.deleting(SEGMENTS, b);
        store.deferSequenceCheck();
        store.update(SEGMENTS, r.first().id(), r.first().version(), merged);
        store.delete(SEGMENTS, r.second().id(), r.second().version());
        store.renumber(c.revisionId(), seq(b), -1);
        rec.renumbered(c.revisionId(), seq(b), -1);
        store.recountSpeakers(c.revisionId());
        Set<UUID> speakers = new LinkedHashSet<>();
        addNonNull(speakers, spkA, spkB, speaker);
        return done(c, "MERGE_SEGMENTS", "Merged lines %d and %d".formatted(seq(a) + 1, seq(b) + 1), rec,
                List.copyOf(speakers), List.of(r.second().id()), List.of());
    }

    @Transactional
    public Done reassign(UUID projectId, ReassignRequest r) {
        EditContext c = editable(projectId);
        Map<String, Object> target = usableSpeaker(c, r.speakerId());
        Map<UUID, Map<String, Object>> rows = store.lockAll(SEGMENTS, r.segments().stream().map(VersionRef::id).toList());
        Recorder rec = new Recorder();
        Set<UUID> speakers = new LinkedHashSet<>(List.of(r.speakerId()));
        int moved = 0;
        for (VersionRef ref : r.segments()) {
            Map<String, Object> seg = own(c, rows.get(ref.id()), "Line");
            expect(seg, ref.version());
            if (r.speakerId().equals(uuid(seg, "speaker_id"))) {
                continue;
            }
            addNonNull(speakers, uuid(seg, "speaker_id"));
            rec.updating(SEGMENTS, seg);
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("speaker_id", r.speakerId());
            ch.put("speaker_assignment", "MANUAL");
            store.update(SEGMENTS, ref.id(), ref.version(), ch);
            moved++;
        }
        if (moved == 0) {
            throw ApiException.badRequest("NOTHING_TO_CHANGE", "All these lines already belong to that speaker.");
        }
        store.recountSpeakers(c.revisionId());
        return done(c, "REASSIGN_SEGMENT", "Moved %d line(s) to %s".formatted(moved, label(target)), rec,
                List.copyOf(speakers), List.of(), List.of());
    }

    // ===================================================================================================== speakers

    @Transactional
    public Done createSpeaker(UUID projectId, CreateSpeakerRequest r) {
        EditContext c = editable(projectId);
        Recorder rec = new Recorder();
        UUID id = insertSpeaker(c, "MANUAL", userText(nz(r.characterName())), userText(nz(r.description())),
                r.suggestedCategory());
        rec.created(SPEAKERS, id);
        Map<String, Object> s = store.read(SPEAKERS, id);
        return done(c, "CREATE_SPEAKER", "Added speaker " + label(s), rec, List.of(), List.of(), List.of());
    }

    private UUID insertSpeaker(EditContext c, String origin, String name, String description, String category) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("project_id", c.projectId());
        v.put("revision_id", c.revisionId());
        v.put("speaker_code", nextCode(c.revisionId()));
        v.put("character_name", name);
        v.put("description", description);
        v.put("suggested_category", category == null || category.isEmpty() ? null : category);
        v.put("voice_assignment", "NONE");
        v.put("voice_settings", Map.of());
        v.put("review_status", "UNREVIEWED");
        v.put("origin", origin);
        v.put("speech_ms", 0L);
        v.put("segment_count", 0);
        Integer order = jdbc.queryForObject("SELECT coalesce(max(display_order), -1) + 1 FROM speakers WHERE revision_id = ?",
                Integer.class, c.revisionId());
        v.put("display_order", order == null ? 0 : order);
        return store.insert(SPEAKERS, v);
    }

    /**
     * Next free SPK_nn code of the revision. Codes never get reused within a revision: codes of speakers that an undo
     * removed again still count (they are in the revision's review history), so a glossary term that names a code can
     * never silently point at a different speaker later.
     */
    private String nextCode(UUID revisionId) {
        int max = 0;
        for (String code : jdbc.queryForList("""
                SELECT speaker_code FROM speakers WHERE revision_id = ?
                UNION
                SELECT coalesce(r -> 'after' ->> 'speaker_code', r -> 'before' ->> 'speaker_code')
                  FROM review_operations o CROSS JOIN LATERAL jsonb_array_elements(o.payload -> 'rows') r
                 WHERE o.revision_id = ? AND r ->> 'table' = 'speakers'""", String.class, revisionId, revisionId)) {
            if (code == null) {
                continue;
            }
            Matcher m = CODE.matcher(code);
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return "SPK_%02d".formatted(max + 1);
    }

    @Transactional
    public Done updateSpeaker(UUID projectId, UUID speakerId, UpdateSpeakerRequest r) {
        EditContext c = editable(projectId);
        Map<String, Object> s = usableSpeaker(c, speakerId);
        expect(s, r.version());
        Map<String, Object> changes = new LinkedHashMap<>();
        Set<String> kinds = new LinkedHashSet<>();
        if (r.characterName() != null && !Objects.equals(userText(r.characterName()), s.get("character_name"))) {
            changes.put("character_name", userText(r.characterName()));
            kinds.add("name");
        }
        if (r.description() != null && !Objects.equals(userText(r.description()), s.get("description"))) {
            changes.put("description", userText(r.description()));
            kinds.add("description");
        }
        if (r.suggestedCategory() != null) {
            String v = r.suggestedCategory().isEmpty() ? null : r.suggestedCategory();
            if (!Objects.equals(v, s.get("suggested_category"))) {
                changes.put("suggested_category", v);
                kinds.add("category");
            }
        }
        if (r.reviewStatus() != null && !r.reviewStatus().equals(s.get("review_status"))) {
            changes.put("review_status", r.reviewStatus());
            kinds.add("review status");
        }
        if (r.voiceProfileId() != null) {
            UUID v;
            try {
                v = r.voiceProfileId().isEmpty() ? null : UUID.fromString(r.voiceProfileId());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_VOICE_PROFILE", "voiceProfileId must be a voice profile id or empty.");
            }
            if (v != null) {
                List<Boolean> active = jdbc.queryForList("SELECT active FROM voice_profiles WHERE id = ? FOR SHARE",
                        Boolean.class, v);
                if (active.isEmpty()) {
                    throw ApiException.notFound("Voice profile");
                }
                if (!active.getFirst()) {
                    throw ApiException.conflict("VOICE_INACTIVE", "That voice profile is deactivated.");
                }
            }
            if (!Objects.equals(v == null ? null : v.toString(), s.get("voice_profile_id"))) {
                changes.put("voice_profile_id", v);
                changes.put("voice_assignment", v == null ? "NONE" : "MANUAL");
                kinds.add("voice");
            }
        }
        if (r.displayOrder() != null && r.displayOrder() != ((Number) s.get("display_order")).intValue()) {
            changes.put("display_order", r.displayOrder());
            kinds.add("order");
        }
        if (changes.isEmpty()) {
            throw ApiException.badRequest("NOTHING_TO_CHANGE", "The request does not change anything.");
        }
        Recorder rec = new Recorder();
        rec.updating(SPEAKERS, s);
        store.update(SPEAKERS, speakerId, r.version(), changes);
        String type = kinds.equals(Set.of("name")) ? "RENAME_SPEAKER"
                : kinds.equals(Set.of("voice")) ? "ASSIGN_VOICE" : "UPDATE_SPEAKER";
        return done(c, type, "Speaker %s: %s".formatted(label(s), String.join(", ", kinds)), rec, List.of(), List.of(),
                List.of());
    }

    @Transactional
    public Done mergeSpeakers(UUID projectId, MergeSpeakersRequest r) {
        EditContext c = editable(projectId);
        UUID targetId = r.target().id();
        Set<UUID> sourceIds = new LinkedHashSet<>();
        for (VersionRef s : r.sources()) {
            if (s.id().equals(targetId) || !sourceIds.add(s.id())) {
                throw ApiException.badRequest("INVALID_MERGE", "List each speaker once, and not the target itself.");
            }
        }
        List<UUID> all = new ArrayList<>(sourceIds);
        all.add(targetId);
        Map<UUID, Map<String, Object>> rows = store.lockAll(SPEAKERS, all);
        Map<String, Object> target = own(c, rows.get(targetId), "Speaker");
        expect(target, r.target().version());
        if (target.get("merged_into_id") != null) {
            throw ApiException.conflict("SPEAKER_MERGED", "The target speaker was itself merged away.");
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (VersionRef ref : r.sources()) {
            Map<String, Object> s = own(c, rows.get(ref.id()), "Speaker");
            expect(s, ref.version());
            if (s.get("merged_into_id") != null) {
                throw ApiException.conflict("SPEAKER_MERGED", "Speaker %s was already merged.".formatted(label(s)));
            }
            sources.add(s);
        }
        String[] sourceArray = sourceIds.stream().map(UUID::toString).toArray(String[]::new);
        List<UUID> segIds = store.lockIds(SEGMENTS, "revision_id = ? AND speaker_id = ANY(?::uuid[])", c.revisionId(),
                sourceArray);
        Map<UUID, Map<String, Object>> segs = store.lockAll(SEGMENTS, segIds);
        Recorder rec = new Recorder();
        for (Map<String, Object> seg : segs.values()) {
            rec.updating(SEGMENTS, seg);
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("speaker_id", targetId);
            ch.put("speaker_assignment", "MANUAL");
            store.update(SEGMENTS, UUID.fromString((String) seg.get("id")), version(seg), ch);
        }
        for (Map<String, Object> s : sources) {
            rec.updating(SPEAKERS, s);
            Map<String, Object> ch = new HashMap<>();
            ch.put("merged_into_id", targetId);
            store.update(SPEAKERS, UUID.fromString((String) s.get("id")), version(s), ch);
        }
        store.recountSpeakers(c.revisionId());
        List<UUID> speakers = new ArrayList<>(all);
        return done(c, "MERGE_SPEAKERS", "Merged %s into %s (%d line(s))".formatted(
                        String.join(", ", sources.stream().map(ReviewService::label).toList()), label(target), segs.size()),
                rec, speakers, List.of(), List.of());
    }

    @Transactional
    public Done splitSpeaker(UUID projectId, UUID speakerId, SplitSpeakerRequest r) {
        EditContext c = editable(projectId);
        Map<String, Object> source = usableSpeaker(c, speakerId);
        expect(source, r.version());
        Map<UUID, Map<String, Object>> rows = store.lockAll(SEGMENTS, r.segments().stream().map(VersionRef::id).toList());
        List<Map<String, Object>> moving = new ArrayList<>();
        for (VersionRef ref : r.segments()) {
            Map<String, Object> seg = own(c, rows.get(ref.id()), "Line");
            expect(seg, ref.version());
            if (!speakerId.equals(uuid(seg, "speaker_id"))) {
                throw ApiException.conflict("LINE_NOT_OF_SPEAKER", "Line %d does not belong to speaker %s."
                        .formatted(seq(seg) + 1, label(source)));
            }
            moving.add(seg);
        }
        Recorder rec = new Recorder();
        UUID newId = insertSpeaker(c, "SPLIT", userText(nz(r.characterName())), null, null);
        rec.created(SPEAKERS, newId);
        for (Map<String, Object> seg : moving) {
            rec.updating(SEGMENTS, seg);
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("speaker_id", newId);
            ch.put("speaker_assignment", "MANUAL");
            store.update(SEGMENTS, UUID.fromString((String) seg.get("id")), version(seg), ch);
        }
        store.recountSpeakers(c.revisionId());
        Map<String, Object> created = store.read(SPEAKERS, newId);
        return done(c, "SPLIT_SPEAKER", "Moved %d line(s) of %s to new speaker %s".formatted(moving.size(),
                label(source), label(created)), rec, List.of(speakerId), List.of(), List.of());
    }

    // ===================================================================================================== undo

    @Transactional
    @SuppressWarnings("unchecked")
    public Done undo(UUID projectId, UUID operationId) {
        EditContext c = editable(projectId);
        Stored op = log.lock(operationId);
        if (op == null || !op.projectId().equals(projectId)) {
            throw ApiException.notFound("Change");
        }
        if (op.undone()) {
            throw ApiException.conflict("ALREADY_UNDONE", "This change was already undone.");
        }
        if (!op.revisionId().equals(c.revisionId())) {
            throw readOnly();
        }
        if (!operationId.equals(log.latestUndoable(c.revisionId()))) {
            throw ApiException.conflict("UNDO_ORDER", "Changes are undone last-first: undo the most recent change first.");
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) op.payload().get("rows");
        List<Map<String, Object>> renumbers = (List<Map<String, Object>>) op.payload().getOrDefault("renumbers", List.of());
        // 1. verify: every row must be exactly as this change left it. Versions only grow; undos of the newer changes
        //    recorded which new version carries the same content as an earlier one, so the current version is traced
        //    back through those equivalences. Any other change (no equivalence) makes the versions differ.
        Map<String, Map<Long, Long>> equivalent = log.equivalences(c.revisionId(), op.seq());
        Map<String, Long> currentVersion = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Table t = Table.of((String) row.get("table"));
            UUID id = UUID.fromString((String) row.get("id"));
            Map<String, Object> current = store.lock(t, id);
            Map<String, Object> after = (Map<String, Object>) row.get("after");
            String key = t.sqlName() + ":" + id;
            boolean changed = "DELETED".equals(row.get("kind")) ? current != null
                    : current == null || after == null
                    || traceBack(equivalent.get(key), version(current)) != version(after);
            if (current != null) {
                currentVersion.put(key, version(current));
            }
            if (changed) {
                throw ApiException.conflict("UNDO_CONFLICT", "Something this change touched was changed again since "
                        + "(%s). Undo was refused and nothing was changed.".formatted(
                        t == SEGMENTS ? "a line" : "a speaker"));
            }
        }
        // 2. restore, newest effect first
        store.deferSequenceCheck();
        for (int i = renumbers.size() - 1; i >= 0; i--) {
            Map<String, Object> rn = renumbers.get(i);
            int above = ((Number) rn.get("aboveSeq")).intValue();
            int delta = ((Number) rn.get("delta")).intValue();
            store.renumber(UUID.fromString((String) rn.get("revisionId")), above + delta, -delta);
        }
        List<UUID> segs = new ArrayList<>();
        List<UUID> spks = new ArrayList<>();
        List<UUID> removedSegs = new ArrayList<>();
        List<UUID> removedSpks = new ArrayList<>();
        List<Map<String, Object>> equivalences = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = rows.get(i);
            Table t = Table.of((String) row.get("table"));
            UUID id = UUID.fromString((String) row.get("id"));
            Map<String, Object> before = (Map<String, Object>) row.get("before");
            Map<String, Object> after = (Map<String, Object>) row.get("after");
            switch ((String) row.get("kind")) {
                case "CREATED" -> {
                    store.delete(t, id, currentVersion.get(t.sqlName() + ":" + id));
                    (t == SEGMENTS ? removedSegs : removedSpks).add(id);
                }
                case "DELETED" -> {
                    // same id and content; a version never issued before (the row was deleted at before.version)
                    Map<String, Object> back = new LinkedHashMap<>(before);
                    back.put("version", version(before) + 1);
                    store.insert(t, back);
                    equivalences.add(equivalence(t, id, version(before) + 1, version(before)));
                    (t == SEGMENTS ? segs : spks).add(id);
                }
                default -> {
                    Map<String, Object> back = new LinkedHashMap<>();
                    for (String col : t.restorable) {
                        back.put(col, before.get(col));
                    }
                    long restored = store.restore(t, id, currentVersion.get(t.sqlName() + ":" + id), back);
                    equivalences.add(equivalence(t, id, restored, version(before)));
                    (t == SEGMENTS ? segs : spks).add(id);
                }
            }
        }
        store.recountSpeakers(c.revisionId());
        log.markUndone(operationId, equivalences);
        // speakers whose line counts changed with the undo
        for (UUID seg : segs) {
            Map<String, Object> s = store.read(SEGMENTS, seg);
            if (s != null && s.get("speaker_id") != null && !spks.contains(uuid(s, "speaker_id"))) {
                spks.add(uuid(s, "speaker_id"));
            }
        }
        OperationView view = new OperationView(op.id(), op.seq(), op.type(), (String) op.payload().get("summary"),
                op.createdAt(), OffsetDateTime.now(clock), false);
        return new Done(view, segs, spks, removedSegs, removedSpks);
    }

    /** Follow "restored version -> earlier version with identical content" links (bounded, never loops). */
    static long traceBack(Map<Long, Long> links, long version) {
        long v = version;
        for (int guard = 0; links != null && guard < 100_000; guard++) {
            Long earlier = links.get(v);
            if (earlier == null || earlier >= v) {
                break;
            }
            v = earlier;
        }
        return v;
    }

    private static Map<String, Object> equivalence(Table t, UUID id, long version, long equivalent) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("table", t.sqlName());
        e.put("id", id.toString());
        e.put("version", version);
        e.put("equivalent", equivalent);
        return e;
    }

    // ===================================================================================================== helpers

    /** Log a change made by another review feature (e.g. applying the glossary) as one undoable operation. */
    public Done finish(EditContext c, String type, String summary, Recorder rec) {
        return done(c, type, summary, rec, List.of(), List.of(), List.of());
    }

    private Done done(EditContext c, String type, String summary, Recorder rec, List<UUID> extraSpeakers,
                      List<UUID> removedSegments, List<UUID> removedSpeakers) {
        Logged logged = log.record(c.projectId(), c.revisionId(), type, summary, rec);
        List<UUID> segs = rec.segmentIds().stream().filter(id -> !removedSegments.contains(id)).toList();
        Set<UUID> spks = new LinkedHashSet<>(rec.speakerIds());
        spks.addAll(extraSpeakers);
        return new Done(new OperationView(logged.id(), logged.seq(), type, summary, OffsetDateTime.now(clock), null, true),
                segs, List.copyOf(spks), removedSegments, removedSpeakers);
    }

    /** "" clears; NUL and other control characters (except tab and newline) are refused. */
    static String userText(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if (ch < 0x20 && ch != '\n' && ch != '\t') {
                throw ApiException.badRequest("INVALID_TEXT", "Text contains control characters.");
            }
        }
        return v;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String part(SplitPart p, Function<SplitPart, String> field) {
        return p == null ? null : userText(field.apply(p));
    }

    static String join(List<Map<String, Object>> words) {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> w : words) {
            b.append(w.get("w") == null ? "" : w.get("w"));
        }
        return b.toString();
    }

    static String separator(String language, String a, String b) {
        if (language != null && NO_SPACE_LANGUAGES.contains(language.toLowerCase())) {
            return "";
        }
        boolean spaced = (a != null && !a.isEmpty() && Character.isWhitespace(a.charAt(a.length() - 1)))
                || (b != null && !b.isEmpty() && Character.isWhitespace(b.charAt(0)));
        return spaced ? "" : " ";
    }

    private static Object orElse(Map<String, Object> row, String col, String fallback) {
        return row.get(col) != null ? row.get(col) : row.get(fallback);
    }

    private static String joinNonBlank(String a, String b) {
        boolean ha = a != null && !a.isBlank();
        boolean hb = b != null && !b.isBlank();
        return ha && hb ? a + " " + b : ha ? a : hb ? b : null;
    }

    private static Object min(Object a, Object b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return ((Number) a).doubleValue() <= ((Number) b).doubleValue() ? a : b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unionFlags(Object a, Object b) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Object f : new Object[] {a, b}) {
            if (f instanceof Map<?, ?> m) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                    if (Boolean.TRUE.equals(e.getValue()) || !out.containsKey(e.getKey())) {
                        out.put(e.getKey(), e.getValue());
                    }
                }
            }
        }
        return out;
    }

    private static long wordMs(Map<String, Object> w, String key) {
        Object v = w.get(key);
        if (!(v instanceof Number n)) {
            throw ApiException.conflict("SPLIT_TIMING", "A word of this line has no timing.");
        }
        return Math.round(n.doubleValue());
    }

    static long num(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }

    static int seq(Map<String, Object> row) {
        return ((Number) row.get("sequence_no")).intValue();
    }

    static String label(Map<String, Object> speaker) {
        Object name = speaker.get("character_name");
        return name == null ? (String) speaker.get("speaker_code") : speaker.get("speaker_code") + " (" + name + ")";
    }

    private static void addNonNull(Set<UUID> set, UUID... ids) {
        for (UUID id : ids) {
            if (id != null) {
                set.add(id);
            }
        }
    }
}
