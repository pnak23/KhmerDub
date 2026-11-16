package com.khmerdub.api.dialogue;

import com.khmerdub.api.job.EngineExceptions.InvalidOutputException;
import com.khmerdub.api.job.ProcessingJob;
import com.khmerdub.api.project.DubProject;
import com.khmerdub.api.project.DubProjectRepository;
import com.khmerdub.api.project.ProjectStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns the worker's dialogue.json (BUILD_DIALOGUE) into a NEW dialogue revision.
 *
 * <p>Runs inside the engine transaction that completes the BUILD_DIALOGUE chunk, so the revision appears completely
 * or not at all. The first revision of a project becomes ACTIVE; later ones wait as READY until the user activates
 * them (the ACTIVE revision, with its reviewed edits, is never replaced implicitly).
 */
@Component
public class DialogueImporter {

    static final int MAX_LINES = 50_000;
    static final int MAX_SPEAKERS = 500;
    static final int MAX_TEXT = 5_000;

    private final DialogueRevisionRepository revisions;
    private final SpeakerRepository speakers;
    private final DialogueSegmentRepository segments;
    private final DubProjectRepository projects;
    private final ObjectMapper json;
    private final Clock clock;

    public DialogueImporter(DialogueRevisionRepository revisions, SpeakerRepository speakers,
                            DialogueSegmentRepository segments, DubProjectRepository projects, ObjectMapper json,
                            Clock clock) {
        this.revisions = revisions;
        this.speakers = speakers;
        this.segments = segments;
        this.projects = projects;
        this.json = json;
        this.clock = clock;
    }

    public Map<String, Object> read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return json.readValue(in, new TypeReference<Map<String, Object>>() { });
        } catch (IOException | JacksonException e) {
            throw new InvalidOutputException("dialogue.json is not valid JSON: " + e.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    public DialogueRevision importRevision(ProcessingJob job, Map<String, Object> dialogue, long durationMs) {
        if (!"khmerdub.dialogue.v1".equals(dialogue.get("format"))) {
            throw new InvalidOutputException("dialogue.json has an unknown format");
        }
        List<Map<String, Object>> speakerList = list(dialogue.get("speakers"), "speakers", MAX_SPEAKERS);
        List<Map<String, Object>> lines = list(dialogue.get("lines"), "lines", MAX_LINES);
        DubProject project = projects.lockById(job.getProjectId()).orElseThrow();
        boolean firstRevision = revisions.findFirstByProjectIdAndStatus(project.getId(), DialogueRevision.Status.ACTIVE)
                .isEmpty();

        Map<String, Object> params = job.getParameters();
        DialogueRevision revision = new DialogueRevision(project.getId(), revisions.maxRevisionNo(project.getId()) + 1,
                job.getId(), (String) params.get("mode"), params);
        if (params.get("backupAssetId") != null) {
            revision.setBackupAssetId(UUID.fromString(params.get("backupAssetId").toString()));
        }
        revision = revisions.saveAndFlush(revision);

        Map<String, UUID> speakerIds = new LinkedHashMap<>();
        int order = 0;
        for (Map<String, Object> s : speakerList) {
            String code = text(s.get("code"), "speaker code", 50);
            if (!code.matches("SPK_\\d{2,3}") || speakerIds.containsKey(code)) {
                throw new InvalidOutputException("Invalid or duplicate speaker code " + code);
            }
            Speaker sp = speakers.save(new Speaker(project.getId(), revision.getId(), code, nonNegative(s.get("speechMs")),
                    (int) nonNegative(s.get("lines")), order++));
            speakerIds.put(code, sp.getId());
        }

        String language = dialogue.get("language") == null ? null : dialogue.get("language").toString();
        List<DialogueSegment> rows = new ArrayList<>(lines.size());
        long previousStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> ln = lines.get(i);
            long start = nonNegative(ln.get("startMs"));
            long end = nonNegative(ln.get("endMs"));
            if (end <= start || end > durationMs + 1_000 || start < previousStart) {
                throw new InvalidOutputException("Line %d has invalid times %d-%d".formatted(i, start, end));
            }
            previousStart = start;
            Object code = ln.get("speaker");
            UUID speakerId = null;
            if (code != null) {
                speakerId = speakerIds.get(code.toString());
                if (speakerId == null) {
                    throw new InvalidOutputException("Line %d refers to unknown speaker %s".formatted(i, code));
                }
            }
            List<Map<String, Object>> words = ln.get("words") instanceof List<?> w ? (List<Map<String, Object>>) w : List.of();
            Float purity = ln.get("purity") instanceof Number n ? n.floatValue() : null;
            Float logprob = ln.get("avgLogprob") instanceof Number n ? n.floatValue() : null;
            Map<String, Object> flags = ln.get("flags") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();
            rows.add(new DialogueSegment(project.getId(), revision.getId(), speakerId, i, start, end, language,
                    text(ln.get("text"), "line text", MAX_TEXT), confidence(words), words, purity, logprob, flags));
        }
        segments.saveAll(rows);

        Map<String, Object> summary = new HashMap<>(dialogue.get("summary") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of());
        summary.put("lines", rows.size());
        summary.put("speakers", speakerIds.size());
        revision.setSummary(summary);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (firstRevision) {
            revision.setStatus(DialogueRevision.Status.ACTIVE);
            revision.setActivatedAt(now);
            if (!project.getStatus().atLeast(ProjectStatus.TRANSCRIBED)) {
                project.setStatus(ProjectStatus.TRANSCRIBED);
            }
        } else {
            revision.setStatus(DialogueRevision.Status.READY);
        }
        if (language != null && project.getDetectedLanguage() == null) {
            project.setDetectedLanguage(language);
        }
        return revisions.save(revision);
    }

    private static Float confidence(List<Map<String, Object>> words) {
        double sum = 0;
        int n = 0;
        for (Map<String, Object> w : words) {
            if (w.get("p") instanceof Number p) {
                sum += p.doubleValue();
                n++;
            }
        }
        return n == 0 ? null : (float) Math.max(0, Math.min(1, sum / n));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value, String what, int max) {
        if (!(value instanceof List<?> l)) {
            throw new InvalidOutputException("dialogue.json: '%s' is missing".formatted(what));
        }
        if (l.size() > max) {
            throw new InvalidOutputException("dialogue.json: too many %s (%d)".formatted(what, l.size()));
        }
        for (Object o : l) {
            if (!(o instanceof Map)) {
                throw new InvalidOutputException("dialogue.json: invalid entry in " + what);
            }
        }
        return (List<Map<String, Object>>) l;
    }

    private static long nonNegative(Object v) {
        if (v instanceof Number n && n.longValue() >= 0) {
            return n.longValue();
        }
        throw new InvalidOutputException("dialogue.json: expected a non-negative number, got " + v);
    }

    private static String text(Object v, String what, int max) {
        if (v == null || v.toString().isBlank()) {
            throw new InvalidOutputException("dialogue.json: empty " + what);
        }
        String s = v.toString().strip();
        return s.length() <= max ? s : s.substring(0, max);
    }
}
