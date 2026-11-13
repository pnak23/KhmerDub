package com.khmerdub.api.job;

import com.khmerdub.api.job.JobViews.EventView;
import com.khmerdub.api.job.JobViews.JobView;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobEngine engine;
    private final JobQueryService queries;

    public JobController(JobEngine engine, JobQueryService queries) {
        this.engine = engine;
        this.queries = queries;
    }

    @GetMapping("/{id}")
    public JobView get(@PathVariable UUID id) {
        return queries.view(id);
    }

    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable UUID id, @RequestParam(defaultValue = "100") int limit) {
        return queries.events(id, limit);
    }

    @PostMapping("/{id}/pause")
    public JobView pause(@PathVariable UUID id) {
        engine.pause(id);
        return queries.view(id);
    }

    @PostMapping("/{id}/resume")
    public JobView resume(@PathVariable UUID id) {
        engine.resume(id);
        return queries.view(id);
    }

    @PostMapping("/{id}/cancel")
    public JobView cancel(@PathVariable UUID id) {
        engine.cancel(id);
        return queries.view(id);
    }

    @PostMapping("/{id}/retry")
    public JobView retry(@PathVariable UUID id) {
        engine.retry(id);
        return queries.view(id);
    }
}
