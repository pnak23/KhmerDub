package com.khmerdub.api.glossary;

import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.glossary.GlossaryViews.ApplyRequest;
import com.khmerdub.api.glossary.GlossaryViews.CreateTermRequest;
import com.khmerdub.api.glossary.GlossaryViews.PreviewRequest;
import com.khmerdub.api.glossary.GlossaryViews.PreviewResult;
import com.khmerdub.api.glossary.GlossaryViews.TermView;
import com.khmerdub.api.glossary.GlossaryViews.UpdateTermRequest;
import com.khmerdub.api.review.ReviewService.Done;
import com.khmerdub.api.review.ReviewViews.OperationResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/glossary")
public class GlossaryController {

    private final GlossaryService glossary;
    private final TranscriptionService views;

    public GlossaryController(GlossaryService glossary, TranscriptionService views) {
        this.glossary = glossary;
        this.views = views;
    }

    @GetMapping
    public List<TermView> list(@PathVariable UUID projectId) {
        return glossary.list(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TermView create(@PathVariable UUID projectId, @Valid @RequestBody CreateTermRequest request) {
        return glossary.create(projectId, request);
    }

    @PatchMapping("/{termId}")
    public TermView update(@PathVariable UUID projectId, @PathVariable UUID termId,
                           @Valid @RequestBody UpdateTermRequest request) {
        return glossary.update(projectId, termId, request);
    }

    @DeleteMapping("/{termId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID projectId, @PathVariable UUID termId, @RequestParam long version) {
        glossary.delete(projectId, termId, version);
    }

    /** Which lines of the active dialogue would change, and how. Changes nothing. */
    @PostMapping("/preview")
    public PreviewResult preview(@PathVariable UUID projectId, @Valid @RequestBody(required = false) PreviewRequest request) {
        return glossary.preview(projectId, request == null ? null : request.termIds());
    }

    /** Apply exactly a previewed change (token from the preview) as one undoable review operation. */
    @PostMapping("/apply")
    public OperationResult apply(@PathVariable UUID projectId, @Valid @RequestBody ApplyRequest request) {
        Done done = glossary.apply(projectId, request);
        return new OperationResult(done.operation(), views.linesById(projectId, done.segmentIds()),
                views.speakersById(projectId, done.speakerIds()), done.removedSegmentIds(), done.removedSpeakerIds());
    }
}
