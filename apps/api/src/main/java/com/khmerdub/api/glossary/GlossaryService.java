package com.khmerdub.api.glossary;

import static com.khmerdub.api.review.ReviewStore.Table.SEGMENTS;

import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.glossary.GlossaryViews.ApplyRequest;
import com.khmerdub.api.glossary.GlossaryViews.CreateTermRequest;
import com.khmerdub.api.glossary.GlossaryViews.PreviewLine;
import com.khmerdub.api.glossary.GlossaryViews.PreviewResult;
import com.khmerdub.api.glossary.GlossaryViews.TermView;
import com.khmerdub.api.glossary.GlossaryViews.UpdateTermRequest;
import com.khmerdub.api.review.ReviewLog.Recorder;
import com.khmerdub.api.review.ReviewService;
import com.khmerdub.api.review.ReviewService.Done;
import com.khmerdub.api.review.ReviewService.EditContext;
import com.khmerdub.api.review.ReviewStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project glossary: CRUD, a preview of which lines would change, and applying that exact preview as one undoable
 * review operation. Applying writes only your Chinese correction field (source_text_edited); the recognised text is
 * never changed. The preview token binds the apply to the previewed state: if any line or term changed since, the
 * apply is refused and nothing changes.
 */
@Service
public class GlossaryService {

    private final GlossaryTermRepository terms;
    private final JdbcTemplate jdbc;
    private final ReviewService review;
    private final ReviewStore store;

    public GlossaryService(GlossaryTermRepository terms, JdbcTemplate jdbc, ReviewService review, ReviewStore store) {
        this.terms = terms;
        this.jdbc = jdbc;
        this.review = review;
        this.store = store;
    }

    // ===================================================================================================== CRUD

    @Transactional(readOnly = true)
    public List<TermView> list(UUID projectId) {
        requireProject(projectId);
        return terms.findByProjectIdOrderByCategoryAscSourceTermAsc(projectId).stream().map(TermView::of).toList();
    }

    @Transactional
    public TermView create(UUID projectId, CreateTermRequest r) {
        lockWritableProject(projectId);
        GlossaryTerm t = new GlossaryTerm(projectId);
        t.setSourceTerm(r.sourceTerm().strip());
        t.setCategory(r.category() == null ? "CHARACTER" : r.category());
        t.setVariants(cleanVariants(r.variants(), t.getSourceTerm()));
        t.setKhmerTerm(blankToNull(r.khmerTerm()));
        t.setNotes(blankToNull(r.notes()));
        t.setSpeakerCode(blankToNull(r.speakerCode()));
        checkUnique(projectId, null, t);
        return TermView.of(terms.saveAndFlush(t));
    }

    @Transactional
    public TermView update(UUID projectId, UUID termId, UpdateTermRequest r) {
        lockWritableProject(projectId);
        GlossaryTerm t = term(projectId, termId);
        if (t.getVersion() != r.version()) {
            throw ReviewStore.conflict();
        }
        if (r.sourceTerm() != null) {
            if (r.sourceTerm().isBlank()) {
                throw ApiException.badRequest("INVALID_TERM", "The term cannot be empty.");
            }
            t.setSourceTerm(r.sourceTerm().strip());
        }
        if (r.category() != null) {
            t.setCategory(r.category());
        }
        if (r.variants() != null) {
            t.setVariants(cleanVariants(r.variants(), t.getSourceTerm()));
        } else if (t.getVariants().contains(t.getSourceTerm())) {
            throw ApiException.badRequest("INVALID_VARIANT", "A variant cannot be the term itself.");
        }
        if (r.khmerTerm() != null) {
            t.setKhmerTerm(blankToNull(r.khmerTerm()));
        }
        if (r.notes() != null) {
            t.setNotes(blankToNull(r.notes()));
        }
        if (r.speakerCode() != null) {
            t.setSpeakerCode(blankToNull(r.speakerCode()));
        }
        checkUnique(projectId, termId, t);
        return TermView.of(terms.saveAndFlush(t));
    }

    @Transactional
    public void delete(UUID projectId, UUID termId, long version) {
        lockWritableProject(projectId);
        GlossaryTerm t = term(projectId, termId);
        if (t.getVersion() != version) {
            throw ReviewStore.conflict();
        }
        terms.delete(t);
    }

    private GlossaryTerm term(UUID projectId, UUID termId) {
        return terms.findById(termId).filter(t -> t.getProjectId().equals(projectId))
                .orElseThrow(() -> ApiException.notFound("Glossary term"));
    }

    /** Variants: trimmed, non-empty, distinct and never the term itself. */
    private static List<String> cleanVariants(List<String> in, String sourceTerm) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : in == null ? List.<String>of() : in) {
            String s = v == null ? "" : v.strip();
            if (s.isEmpty()) {
                throw ApiException.badRequest("INVALID_VARIANT", "A variant cannot be empty.");
            }
            if (s.equals(sourceTerm)) {
                throw ApiException.badRequest("INVALID_VARIANT", "A variant cannot be the term itself.");
            }
            out.add(s);
        }
        return new ArrayList<>(out);
    }

    /** One spelling has one meaning per project: no duplicate terms, and no spelling is both a term and a variant. */
    private void checkUnique(UUID projectId, UUID selfId, GlossaryTerm t) {
        for (GlossaryTerm other : terms.findByProjectIdOrderByCategoryAscSourceTermAsc(projectId)) {
            if (other.getId().equals(selfId)) {
                continue;
            }
            if (other.getSourceTerm().equals(t.getSourceTerm())) {
                throw ApiException.conflict("TERM_EXISTS", "The glossary already has \"%s\"."
                        .formatted(t.getSourceTerm()));
            }
            if (other.getVariants().contains(t.getSourceTerm())) {
                throw ApiException.conflict("VARIANT_CONFLICT", "\"%s\" is listed as a misspelling of \"%s\"."
                        .formatted(t.getSourceTerm(), other.getSourceTerm()));
            }
            for (String v : t.getVariants()) {
                if (v.equals(other.getSourceTerm()) || other.getVariants().contains(v)) {
                    throw ApiException.conflict("VARIANT_CONFLICT", "\"%s\" is already used by the term \"%s\"."
                            .formatted(v, other.getSourceTerm()));
                }
            }
        }
    }

    // ===================================================================================================== preview / apply

    private record Preview(UUID revisionId, String token, List<PreviewLine> lines) {
    }

    @Transactional(readOnly = true)
    public PreviewResult preview(UUID projectId, List<UUID> termIds) {
        requireProject(projectId);
        List<UUID> active = jdbc.queryForList(
                "SELECT id FROM dialogue_revisions WHERE project_id = ? AND status = 'ACTIVE'", UUID.class, projectId);
        if (active.isEmpty()) {
            throw ApiException.conflict("NO_ACTIVE_DIALOGUE", "This project has no active dialogue.");
        }
        return result(compute(active.getFirst(), selectTerms(projectId, termIds)));
    }

    @Transactional
    public Done apply(UUID projectId, ApplyRequest r) {
        EditContext c = review.editable(projectId); // locks the project: nothing else can change meanwhile
        Preview p = compute(c.revisionId(), selectTerms(projectId, r.termIds()));
        if (!p.token().equals(r.token())) {
            throw ApiException.conflict("PREVIEW_STALE", "The dialogue or the glossary changed since the preview. "
                    + "Preview again before applying; nothing was changed.");
        }
        Map<UUID, PreviewLine> byId = new LinkedHashMap<>();
        p.lines().forEach(l -> byId.put(l.segmentId(), l));
        List<PreviewLine> chosen = new ArrayList<>();
        if (r.segmentIds() == null) {
            chosen.addAll(p.lines());
        } else {
            for (UUID id : new LinkedHashSet<>(r.segmentIds())) {
                PreviewLine l = byId.get(id);
                if (l == null) {
                    throw ApiException.badRequest("NOT_IN_PREVIEW", "Only lines from the preview can be changed.");
                }
                chosen.add(l);
            }
        }
        if (chosen.isEmpty()) {
            throw ApiException.badRequest("NOTHING_TO_CHANGE", "No line would change.");
        }
        Map<UUID, Map<String, Object>> rows = store.lockAll(SEGMENTS, chosen.stream().map(PreviewLine::segmentId).toList());
        Recorder rec = new Recorder();
        int replacements = 0;
        for (PreviewLine l : chosen) {
            Map<String, Object> row = ReviewService.own(c, rows.get(l.segmentId()), "Line");
            ReviewService.expect(row, l.version());
            String after = l.after().equals(row.get("source_text")) ? null : l.after();
            rec.updating(SEGMENTS, row);
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("source_text_edited", after);
            store.update(SEGMENTS, l.segmentId(), l.version(), ch);
            replacements += l.matches().stream().mapToInt(GlossaryViews.Match::count).sum();
        }
        return review.finish(c, "APPLY_GLOSSARY", "Glossary: %d replacement(s) in %d line(s)"
                .formatted(replacements, chosen.size()), rec);
    }

    private List<GlossaryTerm> selectTerms(UUID projectId, List<UUID> termIds) {
        List<GlossaryTerm> all = terms.findByProjectIdOrderByCategoryAscSourceTermAsc(projectId);
        if (termIds == null) {
            return all;
        }
        List<GlossaryTerm> chosen = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(termIds)) {
            chosen.add(all.stream().filter(t -> t.getId().equals(id)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Glossary term")));
        }
        return chosen;
    }

    private Preview compute(UUID revisionId, List<GlossaryTerm> selected) {
        GlossaryMatcher matcher = new GlossaryMatcher(selected);
        List<PreviewLine> lines = new ArrayList<>();
        jdbc.query("SELECT id, sequence_no, version, source_text, source_text_edited FROM dialogue_segments "
                + "WHERE revision_id = ? ORDER BY sequence_no", rs -> {
            String before = rs.getString("source_text_edited") != null ? rs.getString("source_text_edited")
                    : rs.getString("source_text");
            GlossaryMatcher.Result res = matcher.apply(before);
            if (!res.text().equals(before)) {
                lines.add(new PreviewLine(rs.getObject("id", UUID.class), rs.getInt("sequence_no"),
                        rs.getLong("version"), before, res.text(), res.matches()));
            }
        }, revisionId);
        StringBuilder basis = new StringBuilder(revisionId.toString());
        selected.stream().sorted(Comparator.comparing(GlossaryTerm::getId))
                .forEach(t -> basis.append('|').append(t.getId()).append(':').append(t.getVersion()));
        for (PreviewLine l : lines) {
            basis.append('|').append(l.segmentId()).append(':').append(l.version()).append(':').append(sha256(l.after()));
        }
        return new Preview(revisionId, sha256(basis.toString()), lines);
    }

    private static PreviewResult result(Preview p) {
        int replacements = p.lines().stream().flatMap(l -> l.matches().stream()).mapToInt(GlossaryViews.Match::count).sum();
        return new PreviewResult(p.revisionId(), p.token(), p.lines().size(), replacements, p.lines());
    }

    // ===================================================================================================== helpers

    private void requireProject(UUID projectId) {
        if (jdbc.queryForList("SELECT 1 FROM dub_projects WHERE id = ?", Integer.class, projectId).isEmpty()) {
            throw ApiException.notFound("Project");
        }
    }

    private void lockWritableProject(UUID projectId) {
        List<Map<String, Object>> p = jdbc.queryForList("SELECT archived_at FROM dub_projects WHERE id = ? FOR UPDATE",
                projectId);
        if (p.isEmpty()) {
            throw ApiException.notFound("Project");
        }
        if (p.getFirst().get("archived_at") != null) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "The project is archived. Restore it first.");
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
