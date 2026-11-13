package com.khmerdub.api.project;

import com.khmerdub.api.config.KhmerDubProperties;
import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.JobViews.JobBrief;
import com.khmerdub.api.media.UploadService;
import com.khmerdub.api.project.ProjectViews.CreateProjectRequest;
import com.khmerdub.api.project.ProjectViews.ProjectDetail;
import com.khmerdub.api.project.ProjectViews.ProjectSummary;
import com.khmerdub.api.project.ProjectViews.StartExtractionRequest;
import com.khmerdub.api.project.ProjectViews.StorageInfo;
import com.khmerdub.api.project.ProjectViews.UpdateProjectRequest;
import com.khmerdub.api.project.ProjectViews.UploadCheckRequest;
import com.khmerdub.api.project.ProjectViews.UploadResult;
import com.khmerdub.api.storage.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProjectController {

    private final ProjectService projects;
    private final UploadService uploads;
    private final StorageService storage;
    private final KhmerDubProperties props;

    public ProjectController(ProjectService projects, UploadService uploads, StorageService storage,
                             KhmerDubProperties props) {
        this.projects = projects;
        this.uploads = uploads;
        this.storage = storage;
        this.props = props;
    }

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDetail create(@Valid @RequestBody CreateProjectRequest request) {
        return projects.create(request);
    }

    @GetMapping("/projects")
    public List<ProjectSummary> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return projects.list(includeArchived);
    }

    @GetMapping("/projects/{id}")
    public ProjectDetail get(@PathVariable UUID id) {
        return projects.detail(id);
    }

    @PatchMapping("/projects/{id}")
    public ProjectDetail update(@PathVariable UUID id, @Valid @RequestBody UpdateProjectRequest request) {
        return projects.update(id, request);
    }

    @PostMapping("/projects/{id}/archive")
    public ProjectDetail archive(@PathVariable UUID id) {
        return projects.setArchived(id, true);
    }

    @PostMapping("/projects/{id}/unarchive")
    public ProjectDetail unarchive(@PathVariable UUID id) {
        return projects.setArchived(id, false);
    }

    @GetMapping("/projects/{id}/jobs")
    public List<JobBrief> jobs(@PathVariable UUID id) {
        return projects.detail(id).jobs();
    }

    /** Validate name, size and free space before sending any bytes. */
    @PostMapping("/projects/{id}/upload/check")
    public Map<String, Object> checkUpload(@PathVariable UUID id, @Valid @RequestBody UploadCheckRequest request) {
        UploadService.Checked checked = uploads.check(id, request.fileName(), request.sizeBytes());
        return Map.of("ok", true, "fileName", checked.name(), "freeBytes", storage.freeBytes());
    }

    /**
     * Raw streamed upload: body = file bytes, Content-Length required, X-File-Name = URL-encoded name.
     * The browser calls this directly on 127.0.0.1:8180 (CORS limited to this endpoint).
     */
    @PostMapping(path = "/projects/{id}/upload", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public UploadResult upload(@PathVariable UUID id,
                               @RequestHeader("X-File-Name") String encodedName,
                               HttpServletRequest request) throws IOException {
        long length = request.getContentLengthLong();
        if (length <= 0) {
            throw new ApiException(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED", "The upload needs a Content-Length.");
        }
        String name = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
        return uploads.receive(id, name, length, request.getInputStream());
    }

    @PostMapping("/projects/{id}/extract-audio")
    @ResponseStatus(HttpStatus.CREATED)
    public JobBrief extractAudio(@PathVariable UUID id, @Valid @RequestBody StartExtractionRequest request) {
        return projects.startExtraction(id, request);
    }

    @GetMapping("/system/storage")
    public StorageInfo storageInfo() throws IOException {
        var store = Files.getFileStore(storage.root());
        return new StorageInfo(storage.root().toString(), store.getUsableSpace(), store.getTotalSpace(),
                props.minFreeDiskBytes(), props.upload().maxSize().toBytes(), props.upload().headroom().toBytes());
    }
}
