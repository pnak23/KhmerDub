package com.khmerdub.api.voice;

import com.khmerdub.api.error.ApiException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The minimal local voice library (M3b-2): named entries with a category, to assign to speakers. There is no TTS
 * provider yet, so provider is always "NONE" and the provider voice id is empty; nothing is sent anywhere and there
 * is no audio preview. "Delete" deactivates (speakers keep their link). Concurrent edits are detected with
 * updated_at (the table has no version column): a request based on an older state is refused.
 */
@Service
public class VoiceProfileService {

    public static final String PROVIDER = "NONE";
    static final String CATEGORIES = "ADULT_MALE|ADULT_FEMALE|BOY|GIRL|ELDERLY_MALE|ELDERLY_FEMALE|CUSTOM";
    private static final String COLUMNS = "id, display_name, provider, provider_voice_id, language, voice_category, "
            + "style, active, created_at, updated_at, "
            + "(SELECT count(*) FROM speakers s WHERE s.voice_profile_id = v.id) AS used_by";

    public record CreateVoiceRequest(@NotBlank @Size(max = 150) String displayName,
                                     @NotNull @Pattern(regexp = CATEGORIES) String voiceCategory,
                                     @Pattern(regexp = "[a-z]{2,3}(-[A-Z]{2})?") String language,
                                     @Size(max = 100) String style) {
    }

    /** expectedUpdatedAt: the updatedAt you last saw. null fields stay unchanged; "" clears style. */
    public record UpdateVoiceRequest(@NotNull OffsetDateTime expectedUpdatedAt,
                                     @Size(min = 1, max = 150) String displayName,
                                     @Pattern(regexp = CATEGORIES) String voiceCategory,
                                     @Pattern(regexp = "[a-z]{2,3}(-[A-Z]{2})?") String language,
                                     @Size(max = 100) String style,
                                     Boolean active) {
    }

    public record VoiceView(UUID id, String displayName, String provider, String providerVoiceId, String language,
                            String voiceCategory, String style, boolean active, long usedBySpeakers,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    private final JdbcTemplate jdbc;

    public VoiceProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<VoiceView> list(boolean includeInactive) {
        return jdbc.query("SELECT " + COLUMNS + " FROM voice_profiles v"
                + (includeInactive ? "" : " WHERE active") + " ORDER BY lower(display_name)", VoiceProfileService::view);
    }

    @Transactional(readOnly = true)
    public VoiceView get(UUID id) {
        return find(id);
    }

    @Transactional
    public VoiceView create(CreateVoiceRequest r) {
        String name = r.displayName().strip();
        lockNames();
        requireFreeName(name, null);
        UUID id = jdbc.queryForObject("INSERT INTO voice_profiles (display_name, provider, provider_voice_id, language, "
                        + "voice_category, style) VALUES (?, ?, '', ?, ?, ?) RETURNING id", UUID.class, name, PROVIDER,
                r.language() == null ? "km-KH" : r.language(), r.voiceCategory(), blankToNull(r.style()));
        return find(id);
    }

    @Transactional
    public VoiceView update(UUID id, UpdateVoiceRequest r) {
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (r.displayName() != null) {
            String name = r.displayName().strip();
            if (name.isEmpty()) {
                throw ApiException.badRequest("INVALID_NAME", "The name cannot be empty.");
            }
            lockNames();
            requireFreeName(name, id);
            sets.add("display_name = ?");
            args.add(name);
        }
        if (r.voiceCategory() != null) {
            sets.add("voice_category = ?");
            args.add(r.voiceCategory());
        }
        if (r.language() != null) {
            sets.add("language = ?");
            args.add(r.language());
        }
        if (r.style() != null) {
            sets.add("style = ?");
            args.add(blankToNull(r.style()));
        }
        if (r.active() != null) {
            sets.add("active = ?");
            args.add(r.active());
        }
        if (sets.isEmpty()) {
            throw ApiException.badRequest("NOTHING_TO_CHANGE", "The request does not change anything.");
        }
        return conditionalUpdate(id, r.expectedUpdatedAt(), String.join(", ", sets), args);
    }

    /** Deactivate (never deletes: speakers that use the profile keep their assignment). */
    @Transactional
    public VoiceView deactivate(UUID id, OffsetDateTime expectedUpdatedAt) {
        return conditionalUpdate(id, expectedUpdatedAt, "active = ?", new ArrayList<>(List.of(false)));
    }

    private VoiceView conditionalUpdate(UUID id, OffsetDateTime expected, String sets, List<Object> args) {
        args.add(id);
        args.add(expected);
        // provider stays NONE / '' (no TTS integration yet), whatever the row held
        int n = jdbc.update("UPDATE voice_profiles SET " + sets + ", provider = '" + PROVIDER + "', provider_voice_id = '' "
                + "WHERE id = ? AND updated_at = ?", args.toArray());
        if (n != 1) {
            find(id); // 404 if it does not exist
            throw ApiException.conflict("EDIT_CONFLICT", "This voice profile was changed in the meantime. Reload and "
                    + "try again; nothing was changed.");
        }
        return find(id);
    }

    private VoiceView find(UUID id) {
        List<VoiceView> v = jdbc.query("SELECT " + COLUMNS + " FROM voice_profiles v WHERE id = ?",
                VoiceProfileService::view, id);
        if (v.isEmpty()) {
            throw ApiException.notFound("Voice profile");
        }
        return v.getFirst();
    }

    /** Serialise name checks (case-insensitive uniqueness, stricter than the database's exact UNIQUE). */
    private void lockNames() {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext('khmerdub.voice_profiles.names'))");
    }

    private void requireFreeName(String name, UUID self) {
        List<UUID> same = jdbc.queryForList("SELECT id FROM voice_profiles WHERE lower(display_name) = lower(?)",
                UUID.class, name);
        if (same.stream().anyMatch(x -> !x.equals(self))) {
            throw ApiException.conflict("NAME_EXISTS", "A voice profile named \"%s\" already exists.".formatted(name));
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static VoiceView view(ResultSet rs, int i) throws SQLException {
        return new VoiceView(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("provider"),
                rs.getString("provider_voice_id"), rs.getString("language"), rs.getString("voice_category"),
                rs.getString("style"), rs.getBoolean("active"), rs.getLong("used_by"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }
}
