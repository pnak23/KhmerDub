package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Glossary CRUD, preview-before-apply (token), canonical spellings protected, apply undoable, recognised text kept. */
@IntegrationTest
class GlossaryTest extends ReviewTestSupport {

    @Test
    void crudValidatesTermsAndVariants() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> t = send(post("/api/projects/{p}/glossary", d.project()),
                map("sourceTerm", "岁岁", "variants", List.of("碎碎", " 穗穗 ", "碎碎"), "khmerTerm", "ស៊ុយស៊ុយ"), 201);
        assertThat(t).containsEntry("category", "CHARACTER").containsEntry("version", 0);
        assertThat(t.get("variants")).isEqualTo(List.of("碎碎", "穗穗"));
        assertThat(send(post("/api/projects/{p}/glossary", d.project()), map("sourceTerm", "岁岁"), 409))
                .containsEntry("code", "TERM_EXISTS");
        assertThat(send(post("/api/projects/{p}/glossary", d.project()), map("sourceTerm", "碎碎"), 409))
                .containsEntry("code", "VARIANT_CONFLICT");
        assertThat(send(post("/api/projects/{p}/glossary", d.project()),
                map("sourceTerm", "回家", "variants", List.of("穗穗")), 409)).containsEntry("code", "VARIANT_CONFLICT");
        assertThat(send(post("/api/projects/{p}/glossary", d.project()),
                map("sourceTerm", "回家", "variants", List.of("回家")), 400)).containsEntry("code", "INVALID_VARIANT");
        send(post("/api/projects/{p}/glossary", d.project()), map("sourceTerm", "x", "category", "PERSON"), 400);

        String id = (String) t.get("id");
        Map<String, Object> updated = send(patch("/api/projects/{p}/glossary/{t}", d.project(), id),
                map("version", 0, "notes", "the daughter", "category", "CHARACTER"), 200);
        assertThat(updated).containsEntry("version", 1).containsEntry("notes", "the daughter");
        assertThat(send(patch("/api/projects/{p}/glossary/{t}", d.project(), id), map("version", 0, "notes", "old"), 409))
                .containsEntry("code", "EDIT_CONFLICT");
        send(delete("/api/projects/{p}/glossary/{t}", d.project(), id).param("version", "0"), null, 409);
        // another project's term is not reachable
        Dialogue other = activeDialogue();
        send(patch("/api/projects/{p}/glossary/{t}", other.project(), id), map("version", 1, "notes", "x"), 404);
        send(delete("/api/projects/{p}/glossary/{t}", d.project(), id).param("version", "1"), null, 204);
        assertThat(sendList(get("/api/projects/{p}/glossary", d.project()), 200)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void applyNeedsTheCurrentPreviewAndCanBeUndone() throws Exception {
        Dialogue d = activeDialogue();
        send(post("/api/projects/{p}/glossary", d.project()), map("sourceTerm", "岁岁", "variants", List.of("碎碎")), 201);
        Map<String, Object> before = export(d.revision());

        Map<String, Object> preview = send(post("/api/projects/{p}/glossary/preview", d.project()), map(), 200);
        assertThat(preview).containsEntry("lines", 1).containsEntry("replacements", 1);
        Map<String, Object> change = ((List<Map<String, Object>>) preview.get("changes")).getFirst();
        assertThat(change).containsEntry("before", "碎碎在哪").containsEntry("after", "岁岁在哪");
        assertThat(export(d.revision())).isEqualTo(before); // a preview changes nothing

        // without a matching token nothing is applied
        assertThat(send(post("/api/projects/{p}/glossary/apply", d.project()), map("token", "made-up"), 409))
                .containsEntry("code", "PREVIEW_STALE");
        String token = (String) preview.get("token");
        // the line changes after the preview: the old token no longer applies
        send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(3)), map("version", 0, "editedText", "km"), 200);
        assertThat(send(post("/api/projects/{p}/glossary/apply", d.project()), map("token", token), 409))
                .containsEntry("code", "PREVIEW_STALE");

        Map<String, Object> fresh = send(post("/api/projects/{p}/glossary/preview", d.project()), map(), 200);
        Map<String, Object> applied = send(post("/api/projects/{p}/glossary/apply", d.project()),
                map("token", fresh.get("token")), 200);
        assertThat(operation(applied)).containsEntry("type", "APPLY_GLOSSARY");
        assertThat(lineRow(d.lines().get(3))).containsEntry("source_text", "碎碎在哪") // recognised text kept
                .containsEntry("source_text_edited", "岁岁在哪");
        // nothing left to apply
        assertThat(send(post("/api/projects/{p}/glossary/preview", d.project()), map(), 200)).containsEntry("lines", 0);

        Map<String, Object> afterEdit = export(d.revision());
        send(post("/api/projects/{p}/review-operations/{o}/undo", d.project(), operation(applied).get("id")), null, 200);
        assertThat(lineRow(d.lines().get(3)).get("source_text_edited")).isNull();
        assertThat(export(d.revision())).isNotEqualTo(afterEdit);
    }

    @Test
    @SuppressWarnings("unchecked")
    void canonicalSpellingsAreProtectedAndSubsetsCanBeApplied() throws Exception {
        Dialogue d = activeDialogue();
        jdbc.update("UPDATE dialogue_segments SET source_text_edited = '王小明和小明' WHERE id = ?", d.lines().get(0));
        jdbc.update("UPDATE dialogue_segments SET source_text_edited = '小明回家吧' WHERE id = ?", d.lines().get(2));
        send(post("/api/projects/{p}/glossary", d.project()), map("sourceTerm", "王小明", "variants", List.of("小明")), 201);
        Map<String, Object> preview = send(post("/api/projects/{p}/glossary/preview", d.project()), map(), 200);
        List<Map<String, Object>> changes = (List<Map<String, Object>>) preview.get("changes");
        assertThat(changes).extracting(c -> c.get("after")).containsExactly("王小明和王小明", "王小明回家吧");
        // apply only the second line
        send(post("/api/projects/{p}/glossary/apply", d.project()), map("token", preview.get("token"),
                "segmentIds", List.of(d.lines().get(2))), 200);
        assertThat(lineRow(d.lines().get(0))).containsEntry("source_text_edited", "王小明和小明");
        assertThat(lineRow(d.lines().get(2))).containsEntry("source_text_edited", "王小明回家吧");
        // a line outside the preview is refused
        Map<String, Object> again = send(post("/api/projects/{p}/glossary/preview", d.project()), map(), 200);
        assertThat(send(post("/api/projects/{p}/glossary/apply", d.project()), map("token", again.get("token"),
                "segmentIds", List.of(UUID.randomUUID())), 400)).containsEntry("code", "NOT_IN_PREVIEW");
    }
}
