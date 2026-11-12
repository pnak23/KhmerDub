-- KhmerDub AI - initial schema (v0.1.0)
-- Conventions:
--   * UUID primary keys (gen_random_uuid() is built into PostgreSQL 13+).
--   * All movie times are BIGINT milliseconds relative to the ORIGINAL movie.
--   * Status columns are VARCHAR + CHECK (simpler to evolve than PostgreSQL enum types).
--   * File paths are stored RELATIVE to the storage root, using forward slashes. Binaries never go in the DB.
--   * Spring Boot is the only writer of job/chunk state.

-- ---------------------------------------------------------------------------
-- updated_at maintenance
-- ---------------------------------------------------------------------------
CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- processing_profiles
-- ---------------------------------------------------------------------------
CREATE TABLE processing_profiles (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                      varchar(100) NOT NULL,
    description               text,
    is_default                boolean      NOT NULL DEFAULT false,
    max_duration_ms           bigint       NOT NULL DEFAULT 10800000,  -- 180 min
    transcription_chunk_ms    bigint       NOT NULL DEFAULT 300000,    -- 5 min
    transcription_overlap_ms  bigint       NOT NULL DEFAULT 5000,
    translation_batch_size    integer      NOT NULL DEFAULT 30,
    whisper_model             varchar(50)  NOT NULL DEFAULT 'large-v3-turbo',
    whisper_compute_type      varchar(20)  NOT NULL DEFAULT 'int8',
    diarization_enabled       boolean      NOT NULL DEFAULT true,
    source_separation_enabled boolean      NOT NULL DEFAULT false,     -- Demucs is slow on CPU
    max_concurrent_cloud_requests integer  NOT NULL DEFAULT 2,
    settings                  jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at                timestamptz  NOT NULL DEFAULT now(),
    updated_at                timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_processing_profiles_name UNIQUE (name),
    CONSTRAINT ck_processing_profiles_max_duration CHECK (max_duration_ms > 0),
    CONSTRAINT ck_processing_profiles_chunk CHECK (transcription_chunk_ms BETWEEN 30000 AND 1800000),
    CONSTRAINT ck_processing_profiles_overlap CHECK (transcription_overlap_ms >= 0
        AND transcription_overlap_ms < transcription_chunk_ms),
    CONSTRAINT ck_processing_profiles_batch CHECK (translation_batch_size BETWEEN 1 AND 200),
    CONSTRAINT ck_processing_profiles_cloud_concurrency CHECK (max_concurrent_cloud_requests BETWEEN 1 AND 32)
);
-- At most one default profile.
CREATE UNIQUE INDEX uq_processing_profiles_single_default ON processing_profiles (is_default) WHERE is_default;
CREATE TRIGGER trg_processing_profiles_updated_at BEFORE UPDATE ON processing_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

INSERT INTO processing_profiles (name, description, is_default)
VALUES ('balanced', 'Default for CPU-only processing of movies up to 3 hours', true);

-- ---------------------------------------------------------------------------
-- voice_profiles (personal voice library)
-- ---------------------------------------------------------------------------
CREATE TABLE voice_profiles (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    display_name       varchar(150) NOT NULL,
    provider           varchar(50)  NOT NULL,
    provider_voice_id  varchar(200) NOT NULL,
    language           varchar(20)  NOT NULL DEFAULT 'km-KH',
    voice_category     varchar(30)  NOT NULL,
    style              varchar(100),
    settings           jsonb        NOT NULL DEFAULT '{}'::jsonb,  -- default pitch/rate/etc. for this profile
    supported_settings jsonb        NOT NULL DEFAULT '{}'::jsonb,  -- what the provider lets us change
    active             boolean      NOT NULL DEFAULT true,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_voice_profiles_display_name UNIQUE (display_name),
    CONSTRAINT ck_voice_profiles_category CHECK (voice_category IN
        ('ADULT_MALE', 'ADULT_FEMALE', 'BOY', 'GIRL', 'ELDERLY_MALE', 'ELDERLY_FEMALE', 'CUSTOM'))
);
CREATE INDEX ix_voice_profiles_provider ON voice_profiles (provider, active);
CREATE TRIGGER trg_voice_profiles_updated_at BEFORE UPDATE ON voice_profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- dub_projects
-- ---------------------------------------------------------------------------
CREATE TABLE dub_projects (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  varchar(200) NOT NULL,
    source_language       varchar(20),               -- NULL = auto-detect
    detected_language     varchar(20),
    target_language       varchar(20)  NOT NULL DEFAULT 'km',
    translation_style     varchar(50)  NOT NULL DEFAULT 'NATURAL',
    status                varchar(30)  NOT NULL DEFAULT 'CREATED',
    duration_ms           bigint,
    processing_profile_id uuid REFERENCES processing_profiles (id) ON DELETE RESTRICT,
    version               bigint       NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_dub_projects_status CHECK (status IN
        ('CREATED', 'IMPORTED', 'TRANSCRIBED', 'TRANSLATED', 'VOICED', 'EXPORTED', 'ARCHIVED')),
    CONSTRAINT ck_dub_projects_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);
CREATE INDEX ix_dub_projects_updated ON dub_projects (updated_at DESC);
CREATE TRIGGER trg_dub_projects_updated_at BEFORE UPDATE ON dub_projects
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- media_assets (files on disk; RESTRICT so deleting a project never silently orphans/loses originals)
-- ---------------------------------------------------------------------------
CREATE TABLE media_assets (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      uuid          NOT NULL REFERENCES dub_projects (id) ON DELETE RESTRICT,
    asset_type      varchar(40)   NOT NULL,
    filename        varchar(500)  NOT NULL,
    storage_path    varchar(1000) NOT NULL,
    mime_type       varchar(100),
    file_size_bytes bigint,
    sha256          char(64),
    metadata        jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_media_assets_storage_path UNIQUE (storage_path),
    CONSTRAINT ck_media_assets_type CHECK (asset_type IN
        ('ORIGINAL_VIDEO', 'EXTRACTED_AUDIO', 'AUDIO_CHUNK', 'TRANSCRIPT', 'DIARIZATION', 'SPEAKER_EMBEDDING',
         'TRANSLATION', 'TTS_CLIP', 'VOICE_PREVIEW', 'BACKGROUND_AUDIO', 'DUB_AUDIO', 'MIXED_AUDIO',
         'SUBTITLE', 'EXPORT_VIDEO', 'OTHER')),
    CONSTRAINT ck_media_assets_size CHECK (file_size_bytes IS NULL OR file_size_bytes >= 0),
    -- Relative, forward-slash paths only: no absolute paths, drive letters, backslashes or '..' segments.
    CONSTRAINT ck_media_assets_relative_path CHECK (
        storage_path <> ''
        AND storage_path !~ '^/'
        AND storage_path !~ '^[A-Za-z]:'
        AND position('\' IN storage_path) = 0
        AND storage_path !~ '(^|/)\.\.(/|$)')
);
CREATE INDEX ix_media_assets_project_type ON media_assets (project_id, asset_type);
-- One original movie per project.
CREATE UNIQUE INDEX uq_media_assets_one_original ON media_assets (project_id) WHERE asset_type = 'ORIGINAL_VIDEO';

-- ---------------------------------------------------------------------------
-- processing_jobs
-- ---------------------------------------------------------------------------
CREATE TABLE processing_jobs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    uuid         NOT NULL REFERENCES dub_projects (id) ON DELETE CASCADE,
    job_type      varchar(40)  NOT NULL,
    status        varchar(20)  NOT NULL DEFAULT 'QUEUED',
    current_stage varchar(40),
    progress      numeric(5, 2) NOT NULL DEFAULT 0,
    retry_count   integer      NOT NULL DEFAULT 0,
    error_message text,
    parameters    jsonb        NOT NULL DEFAULT '{}'::jsonb,
    version       bigint       NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    started_at    timestamptz,
    finished_at   timestamptz,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_processing_jobs_type CHECK (job_type IN
        ('IMPORT', 'TRANSCRIBE', 'TRANSLATE', 'GENERATE_AUDIO', 'REGENERATE_CLIPS', 'EXPORT', 'FULL_PIPELINE')),
    CONSTRAINT ck_processing_jobs_status CHECK (status IN
        ('QUEUED', 'RUNNING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_processing_jobs_progress CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT ck_processing_jobs_retry CHECK (retry_count >= 0)
);
-- At most one active job of each type per project.
CREATE UNIQUE INDEX uq_processing_jobs_one_active ON processing_jobs (project_id, job_type)
    WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED');
CREATE INDEX ix_processing_jobs_status ON processing_jobs (status);
CREATE INDEX ix_processing_jobs_project ON processing_jobs (project_id, created_at DESC);
CREATE TRIGGER trg_processing_jobs_updated_at BEFORE UPDATE ON processing_jobs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- processing_chunks
-- ---------------------------------------------------------------------------
CREATE TABLE processing_chunks (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id           uuid        NOT NULL REFERENCES processing_jobs (id) ON DELETE CASCADE,
    stage            varchar(40) NOT NULL,
    chunk_index      integer     NOT NULL,
    start_ms         bigint      NOT NULL,
    end_ms           bigint      NOT NULL,
    status           varchar(20) NOT NULL DEFAULT 'PENDING',
    retry_count      integer     NOT NULL DEFAULT 0,
    input_hash       char(64),
    result_asset_id  uuid REFERENCES media_assets (id) ON DELETE SET NULL,
    error_details    text,
    -- Recovery: each dispatch gets a fresh token. A late result carrying an old token is ignored.
    dispatch_token   uuid,
    lease_expires_at timestamptz,
    dispatched_at    timestamptz,
    started_at       timestamptz,
    completed_at     timestamptz,
    version          bigint      NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_processing_chunks_job_stage_index UNIQUE (job_id, stage, chunk_index),
    CONSTRAINT ck_processing_chunks_stage CHECK (stage IN
        ('EXTRACT_AUDIO', 'DETECT_LANGUAGE', 'TRANSCRIBE', 'DIARIZE', 'RECONCILE_SPEAKERS', 'TRANSLATE', 'TTS',
         'ASSEMBLE', 'MIX', 'SUBTITLE', 'EXPORT', 'VALIDATE')),
    CONSTRAINT ck_processing_chunks_status CHECK (status IN
        ('PENDING', 'DISPATCHED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'SKIPPED')),
    CONSTRAINT ck_processing_chunks_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_processing_chunks_times CHECK (start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT ck_processing_chunks_retry CHECK (retry_count >= 0)
);
CREATE INDEX ix_processing_chunks_recovery ON processing_chunks (status, lease_expires_at);
CREATE INDEX ix_processing_chunks_job_status ON processing_chunks (job_id, status);
CREATE TRIGGER trg_processing_chunks_updated_at BEFORE UPDATE ON processing_chunks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- job_events (append-only history)
-- ---------------------------------------------------------------------------
CREATE TABLE job_events (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id     uuid        NOT NULL REFERENCES processing_jobs (id) ON DELETE CASCADE,
    chunk_id   uuid REFERENCES processing_chunks (id) ON DELETE SET NULL,
    event_type varchar(40) NOT NULL,
    level      varchar(10) NOT NULL DEFAULT 'INFO',
    message    text,
    payload    jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_job_events_level CHECK (level IN ('DEBUG', 'INFO', 'WARN', 'ERROR'))
);
CREATE INDEX ix_job_events_job_created ON job_events (job_id, created_at);

-- ---------------------------------------------------------------------------
-- speakers (characters)
-- ---------------------------------------------------------------------------
CREATE TABLE speakers (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id         uuid         NOT NULL REFERENCES dub_projects (id) ON DELETE CASCADE,
    speaker_code       varchar(50)  NOT NULL,  -- stable across chunks, e.g. SPK_01
    character_name     varchar(150),
    description        text,
    -- Suggestion only: diarization separates voices, it does not reliably tell gender or age.
    suggested_category varchar(30),
    voice_profile_id   uuid REFERENCES voice_profiles (id) ON DELETE SET NULL,
    voice_assignment   varchar(20)  NOT NULL DEFAULT 'NONE',
    voice_settings     jsonb        NOT NULL DEFAULT '{}'::jsonb,  -- per-character overrides
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_speakers_project_code UNIQUE (project_id, speaker_code),
    CONSTRAINT ck_speakers_suggested_category CHECK (suggested_category IS NULL OR suggested_category IN
        ('ADULT_MALE', 'ADULT_FEMALE', 'BOY', 'GIRL', 'ELDERLY_MALE', 'ELDERLY_FEMALE', 'CUSTOM')),
    CONSTRAINT ck_speakers_voice_assignment CHECK (voice_assignment IN ('NONE', 'SUGGESTED', 'MANUAL'))
);
CREATE INDEX ix_speakers_voice_profile ON speakers (voice_profile_id);
CREATE TRIGGER trg_speakers_updated_at BEFORE UPDATE ON speakers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- dialogue_segments
-- ---------------------------------------------------------------------------
CREATE TABLE dialogue_segments (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id        uuid        NOT NULL REFERENCES dub_projects (id) ON DELETE CASCADE,
    speaker_id        uuid REFERENCES speakers (id) ON DELETE SET NULL,
    sequence_no       integer     NOT NULL,
    start_ms          bigint      NOT NULL,
    end_ms            bigint      NOT NULL,
    source_language   varchar(20),
    source_text       text        NOT NULL,
    translated_text   text,         -- AI output only; never written by the user
    edited_text       text,         -- the user's Khmer; never written by AI
    translation_model varchar(100),
    emotion           varchar(40),
    confidence        real,
    review_status     varchar(20) NOT NULL DEFAULT 'PENDING',
    version           bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    -- DEFERRABLE so a whole block of segments can be renumbered in one transaction.
    CONSTRAINT uq_dialogue_segments_project_seq UNIQUE (project_id, sequence_no) DEFERRABLE INITIALLY IMMEDIATE,
    CONSTRAINT ck_dialogue_segments_seq CHECK (sequence_no >= 0),
    CONSTRAINT ck_dialogue_segments_times CHECK (start_ms >= 0 AND end_ms > start_ms),
    CONSTRAINT ck_dialogue_segments_confidence CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_dialogue_segments_review CHECK (review_status IN
        ('PENDING', 'NEEDS_REVIEW', 'APPROVED', 'REJECTED'))
);
CREATE INDEX ix_dialogue_segments_project_start ON dialogue_segments (project_id, start_ms);
CREATE INDEX ix_dialogue_segments_speaker ON dialogue_segments (speaker_id);
CREATE TRIGGER trg_dialogue_segments_updated_at BEFORE UPDATE ON dialogue_segments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- audio_clips (one per dialogue segment; asset_id = the version currently in use)
-- ---------------------------------------------------------------------------
CREATE TABLE audio_clips (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    dialogue_segment_id uuid         NOT NULL REFERENCES dialogue_segments (id) ON DELETE CASCADE,
    asset_id            uuid REFERENCES media_assets (id) ON DELETE RESTRICT,
    provider            varchar(50),
    provider_voice_id   varchar(200),
    duration_ms         bigint,
    generation_status   varchar(20)  NOT NULL DEFAULT 'PENDING',
    error_message       text,
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_audio_clips_segment UNIQUE (dialogue_segment_id),
    CONSTRAINT ck_audio_clips_status CHECK (generation_status IN
        ('PENDING', 'QUEUED', 'GENERATING', 'READY', 'FAILED', 'STALE')),
    CONSTRAINT ck_audio_clips_duration CHECK (duration_ms IS NULL OR duration_ms >= 0)
);
CREATE INDEX ix_audio_clips_asset ON audio_clips (asset_id);
CREATE TRIGGER trg_audio_clips_updated_at BEFORE UPDATE ON audio_clips
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- audio_clip_versions (immutable history; input_hash = hash of text + voice + settings + provider)
-- ---------------------------------------------------------------------------
CREATE TABLE audio_clip_versions (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    audio_clip_id     uuid         NOT NULL REFERENCES audio_clips (id) ON DELETE CASCADE,
    version_no        integer      NOT NULL,
    asset_id          uuid         NOT NULL REFERENCES media_assets (id) ON DELETE RESTRICT,
    input_hash        char(64)     NOT NULL,
    provider          varchar(50)  NOT NULL,
    provider_voice_id varchar(200) NOT NULL,
    duration_ms       bigint,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_audio_clip_versions_clip_version UNIQUE (audio_clip_id, version_no),
    -- Never generate the same TTS input twice for the same clip.
    CONSTRAINT uq_audio_clip_versions_clip_hash UNIQUE (audio_clip_id, input_hash),
    CONSTRAINT ck_audio_clip_versions_version CHECK (version_no >= 1)
);
-- Cache lookup across clips (identical text + voice + settings can reuse audio).
CREATE INDEX ix_audio_clip_versions_input_hash ON audio_clip_versions (input_hash);
CREATE INDEX ix_audio_clip_versions_asset ON audio_clip_versions (asset_id);

-- ---------------------------------------------------------------------------
-- subtitle_tracks
-- ---------------------------------------------------------------------------
CREATE TABLE subtitle_tracks (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     uuid        NOT NULL REFERENCES dub_projects (id) ON DELETE CASCADE,
    language       varchar(20) NOT NULL,
    format         varchar(10) NOT NULL,
    media_asset_id uuid REFERENCES media_assets (id) ON DELETE RESTRICT,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_subtitle_tracks_project_lang_format UNIQUE (project_id, language, format),
    CONSTRAINT ck_subtitle_tracks_format CHECK (format IN ('SRT', 'VTT', 'ASS'))
);
CREATE INDEX ix_subtitle_tracks_asset ON subtitle_tracks (media_asset_id);
CREATE TRIGGER trg_subtitle_tracks_updated_at BEFORE UPDATE ON subtitle_tracks
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
