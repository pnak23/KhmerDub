package com.khmerdub.api.internal;

import com.khmerdub.api.job.EngineExceptions.AttemptNotFoundException;
import com.khmerdub.api.job.EngineExceptions.FencedException;
import com.khmerdub.api.job.EngineExceptions.InvalidOutputException;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.WorkerProtocol.ClaimRequest;
import com.khmerdub.api.job.WorkerProtocol.ClaimResponse;
import com.khmerdub.api.job.WorkerProtocol.CompleteRequest;
import com.khmerdub.api.job.WorkerProtocol.FailRequest;
import com.khmerdub.api.job.WorkerProtocol.HeartbeatRequest;
import com.khmerdub.api.job.WorkerProtocol.HeartbeatResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Worker protocol (token-protected by InternalTokenFilter). Status codes the worker relies on:
 * 200 = go on; 409 = fenced, stop and publish nothing; 404 = unknown attempt; 422 = result rejected.
 */
@RestController
@RequestMapping("/internal/attempts/{attemptId}")
public class InternalAttemptController {

    private final JobEngine engine;

    public InternalAttemptController(JobEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/claim")
    public ClaimResponse claim(@PathVariable UUID attemptId, @Valid @RequestBody ClaimRequest request) {
        return engine.claim(attemptId, request.worker());
    }

    @PostMapping("/heartbeat")
    public HeartbeatResponse heartbeat(@PathVariable UUID attemptId, @Valid @RequestBody HeartbeatRequest request) {
        return engine.heartbeat(attemptId, request.progress());
    }

    @PostMapping("/complete")
    public Map<String, Object> complete(@PathVariable UUID attemptId, @Valid @RequestBody CompleteRequest request) {
        engine.complete(attemptId, request);
        return Map.of("accepted", true);
    }

    @PostMapping("/fail")
    public Map<String, Object> fail(@PathVariable UUID attemptId, @Valid @RequestBody FailRequest request) {
        engine.fail(attemptId, request);
        return Map.of("recorded", true);
    }

    @ExceptionHandler(FencedException.class)
    ResponseEntity<ProblemDetail> fenced(FencedException e) {
        return problem(HttpStatus.CONFLICT, "FENCED", e.getMessage());
    }

    @ExceptionHandler(AttemptNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(AttemptNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "UNKNOWN_ATTEMPT", e.getMessage());
    }

    @ExceptionHandler(InvalidOutputException.class)
    ResponseEntity<ProblemDetail> invalidOutput(InvalidOutputException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_OUTPUT", e.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("code", code);
        return ResponseEntity.status(status).body(pd);
    }
}
