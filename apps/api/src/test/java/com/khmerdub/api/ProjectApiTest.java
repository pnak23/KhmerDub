package com.khmerdub.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class ProjectApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute(EngineFixtures.TRUNCATE);
    }

    @Test
    void createListArchiveAndRestore() throws Exception {
        String body = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  My Movie  \",\"sourceLanguage\":\"zh\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.name").value("My Movie"))
                .andExpect(jsonPath("$.summary.sourceLanguage").value("zh"))
                .andExpect(jsonPath("$.summary.status").value("CREATED"))
                .andExpect(jsonPath("$.targetLanguage").value("km"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.summary.id");

        mvc.perform(get("/api/projects")).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(post("/api/projects/{id}/archive", id)).andExpect(jsonPath("$.summary.archived").value(true));
        mvc.perform(get("/api/projects")).andExpect(jsonPath("$", Matchers.hasSize(0)));
        mvc.perform(get("/api/projects").param("includeArchived", "true")).andExpect(jsonPath("$", Matchers.hasSize(1)));
        mvc.perform(post("/api/projects/{id}/unarchive", id)).andExpect(jsonPath("$.summary.archived").value(false));
    }

    @Test
    void invalidInputReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"sourceLanguage\":\"not a language\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", Matchers.containsInAnyOrder("name", "sourceLanguage")));
    }

    @Test
    void renameAndUnknownProject() throws Exception {
        String body = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.summary.id");
        mvc.perform(patch("/api/projects/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"B\"}"))
                .andExpect(jsonPath("$.summary.name").value("B"));
        mvc.perform(get("/api/projects/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void extractionNeedsAnInspectedMovie() throws Exception {
        String body = mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"A\"}"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.summary.id");
        mvc.perform(post("/api/projects/{id}/extract-audio", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_INSPECTED"));
    }

    @Test
    void storageInfoAndProfiles() throws Exception {
        mvc.perform(get("/api/system/storage"))
                .andExpect(jsonPath("$.freeBytes").isNumber())
                .andExpect(jsonPath("$.maxUploadBytes").value(20L * 1024 * 1024 * 1024));
        mvc.perform(get("/api/processing-profiles"))
                .andExpect(jsonPath("$[0].name").value("balanced"))
                .andExpect(jsonPath("$[0].extractionSegmentMs").value(600000));
    }
}
