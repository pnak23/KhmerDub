package com.khmerdub.api.review;

import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.review.ReviewService.Done;
import com.khmerdub.api.review.ReviewViews.CreateSpeakerRequest;
import com.khmerdub.api.review.ReviewViews.EditSegmentRequest;
import com.khmerdub.api.review.ReviewViews.MergeSegmentsRequest;
import com.khmerdub.api.review.ReviewViews.MergeSpeakersRequest;
import com.khmerdub.api.review.ReviewViews.OperationResult;
import com.khmerdub.api.review.ReviewViews.OperationView;
import com.khmerdub.api.review.ReviewViews.ReassignRequest;
import com.khmerdub.api.review.ReviewViews.SplitSegmentRequest;
import com.khmerdub.api.review.ReviewViews.SplitSpeakerRequest;
import com.khmerdub.api.review.ReviewViews.UpdateSpeakerRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Review API (M3b-2). Every route is nested under its project, so a line, speaker or change of another project
 * answers 404. Each change returns the operation (for undo) and the current state of what it touched.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ReviewController {

    private final ReviewService review;
    private final ReviewQueries queries;
    private final TranscriptionService views;

    public ReviewController(ReviewService review, ReviewQueries queries, TranscriptionService views) {
        this.review = review;
        this.queries = queries;
        this.views = views;
    }

    @PatchMapping("/segments/{segmentId}")
    public OperationResult editSegment(@PathVariable UUID projectId, @PathVariable UUID segmentId,
                                       @Valid @RequestBody EditSegmentRequest request) {
        return result(projectId, review.editSegment(projectId, segmentId, request));
    }

    @PostMapping("/segments/{segmentId}/split")
    public OperationResult splitSegment(@PathVariable UUID projectId, @PathVariable UUID segmentId,
                                        @Valid @RequestBody SplitSegmentRequest request) {
        return result(projectId, review.splitSegment(projectId, segmentId, request));
    }

    @PostMapping("/segments/merge")
    public OperationResult mergeSegments(@PathVariable UUID projectId, @Valid @RequestBody MergeSegmentsRequest request) {
        return result(projectId, review.mergeSegments(projectId, request));
    }

    @PostMapping("/segments/reassign")
    public OperationResult reassign(@PathVariable UUID projectId, @Valid @RequestBody ReassignRequest request) {
        return result(projectId, review.reassign(projectId, request));
    }

    @PostMapping("/speakers")
    public OperationResult createSpeaker(@PathVariable UUID projectId, @Valid @RequestBody CreateSpeakerRequest request) {
        return result(projectId, review.createSpeaker(projectId, request));
    }

    @PatchMapping("/speakers/{speakerId}")
    public OperationResult updateSpeaker(@PathVariable UUID projectId, @PathVariable UUID speakerId,
                                         @Valid @RequestBody UpdateSpeakerRequest request) {
        return result(projectId, review.updateSpeaker(projectId, speakerId, request));
    }

    @PostMapping("/speakers/merge")
    public OperationResult mergeSpeakers(@PathVariable UUID projectId, @Valid @RequestBody MergeSpeakersRequest request) {
        return result(projectId, review.mergeSpeakers(projectId, request));
    }

    @PostMapping("/speakers/{speakerId}/split")
    public OperationResult splitSpeaker(@PathVariable UUID projectId, @PathVariable UUID speakerId,
                                        @Valid @RequestBody SplitSpeakerRequest request) {
        return result(projectId, review.splitSpeaker(projectId, speakerId, request));
    }

    /** Changes of a revision (newest first). Only the newest not-undone change of the ACTIVE revision is undoable. */
    @GetMapping("/review-operations")
    public List<OperationView> operations(@PathVariable UUID projectId, @RequestParam(required = false) UUID revisionId,
                                          @RequestParam(defaultValue = "100") int limit) {
        return queries.operations(projectId, revisionId, limit);
    }

    @PostMapping("/review-operations/{operationId}/undo")
    public OperationResult undo(@PathVariable UUID projectId, @PathVariable UUID operationId) {
        return result(projectId, review.undo(projectId, operationId));
    }

    private OperationResult result(UUID projectId, Done done) {
        return new OperationResult(done.operation(), views.linesById(projectId, done.segmentIds()),
                views.speakersById(projectId, done.speakerIds()), done.removedSegmentIds(), done.removedSpeakerIds());
    }
}
