package com.khmerdub.api.dialogue;

import com.khmerdub.api.dialogue.DialogueViews.ActivateRequest;
import com.khmerdub.api.dialogue.DialogueViews.DialoguePage;
import com.khmerdub.api.dialogue.DialogueViews.RevisionView;
import com.khmerdub.api.dialogue.DialogueViews.SpeakerView;
import com.khmerdub.api.dialogue.DialogueViews.StartTranscriptionRequest;
import com.khmerdub.api.job.JobViews.JobBrief;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class DialogueController {

    private final TranscriptionService service;

    public DialogueController(TranscriptionService service) {
        this.service = service;
    }

    @PostMapping("/transcribe")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobBrief transcribe(@PathVariable UUID projectId, @Valid @RequestBody StartTranscriptionRequest request) {
        return service.start(projectId, request);
    }

    @GetMapping("/dialogue-revisions")
    public List<RevisionView> revisions(@PathVariable UUID projectId) {
        return service.revisions(projectId);
    }

    @PostMapping("/dialogue-revisions/{revisionId}/activate")
    public RevisionView activate(@PathVariable UUID projectId, @PathVariable UUID revisionId,
                                 @RequestBody(required = false) ActivateRequest request) {
        return service.activate(projectId, revisionId, request != null && request.confirm());
    }

    @GetMapping("/speakers")
    public List<SpeakerView> speakers(@PathVariable UUID projectId, @RequestParam(required = false) UUID revisionId) {
        return service.speakers(projectId, revisionId);
    }

    @GetMapping("/dialogue")
    public DialoguePage dialogue(@PathVariable UUID projectId, @RequestParam(required = false) UUID revisionId,
                                 @RequestParam(defaultValue = "0") int fromSeq,
                                 @RequestParam(defaultValue = "200") int limit) {
        return service.dialogue(projectId, revisionId, fromSeq, limit);
    }
}
