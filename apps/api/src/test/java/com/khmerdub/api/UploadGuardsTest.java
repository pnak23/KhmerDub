package com.khmerdub.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.project.ProjectViews.CreateProjectRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Disk-space and size limits, checked before any bytes are accepted. */
@IntegrationTest
@TestPropertySource(properties = {"khmerdub.min-free-disk-gb=1000000", "khmerdub.upload.max-size=10MB"})
class UploadGuardsTest {

    @Autowired MockMvc mvc;
    @Autowired ProjectService projects;

    @Test
    void notEnoughFreeSpaceIsReportedClearly() throws Exception {
        UUID p = projects.create(new CreateProjectRequest("Disk test", null)).summary().id();
        mvc.perform(post("/api/projects/{id}/upload/check", p).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"movie.mp4\",\"sizeBytes\":1000}"))
                .andExpect(status().isInsufficientStorage())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STORAGE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Not enough free disk space")));
    }

    @Test
    void filesAboveTheLimitAreRefused() throws Exception {
        UUID p = projects.create(new CreateProjectRequest("Size test", null)).summary().id();
        mvc.perform(post("/api/projects/{id}/upload/check", p).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"movie.mp4\",\"sizeBytes\":20000000}"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }
}
