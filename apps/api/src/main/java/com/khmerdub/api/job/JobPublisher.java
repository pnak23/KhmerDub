package com.khmerdub.api.job;

import com.khmerdub.api.job.JobEngine.JobChanged;
import com.khmerdub.api.project.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes committed job state to the UI: /topic/jobs/{jobId} (full job) and /topic/projects (project summary).
 * Runs after commit, so clients never see state that was rolled back.
 */
@Component
public class JobPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobPublisher.class);

    private final JobQueryService queries;
    private final ProjectService projects;
    private final SimpMessagingTemplate messaging;

    public JobPublisher(JobQueryService queries, ProjectService projects, SimpMessagingTemplate messaging) {
        this.queries = queries;
        this.projects = projects;
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onJobChanged(JobChanged event) {
        try {
            messaging.convertAndSend("/topic/jobs/" + event.jobId(), queries.view(event.jobId()));
            messaging.convertAndSend("/topic/projects", projects.summary(event.projectId()));
        } catch (RuntimeException e) {
            log.warn("Could not publish job update for {}: {}", event.jobId(), e.toString());
        }
    }
}
