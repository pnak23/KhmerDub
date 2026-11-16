-- KhmerDub AI - M3b: transcription, speaker identification and review.
--
-- Additive only: no existing row is changed or deleted. speakers/dialogue_segments are empty in every M2
-- database (M2 never writes them); the precheck below aborts loudly instead of guessing if that is not true.
--
-- Reprocessing safety: every TRANSCRIBE run builds a NEW dialogue revision. Speakers and dialogue lines belong to
-- a revision. Only one revision is ACTIVE; older ones are SUPERSEDED (kept, read-only) and a failed run never
-- touches the active one.
--
-- Privacy: speaker embeddings (SPEAKER_EMBEDDING) and voice samples (SPEAKER_SAMPLE) are files in separate private
-- folders; no column in this schema stores an embedding vector.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM speakers) OR EXISTS (SELECT 1 FROM dialogue_segments) THEN
        RAISE EXCEPTION 'V3 expects empty speakers/dialogue_segments tables (M2 never writes them)';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- New stages and asset types; private storage locations
-- ---------------------------------------------------------------------------
ALTER TABLE processing_chunks
    DROP CONSTRAINT ck_processing_chunks_stage,
    ADD CONSTRAINT ck_processing_chunks_stage CHECK (stage IN
        ('INSPECT_MEDIA', 'EXTRACT_AUDIO', 'MERGE_AUDIO', 'DETECT_LANGUAGE', 'SPEECH_MAP', 'TRANSCRIBE', 'DIARIZE',
         'RECONCILE_SPEAKERS', 'BUILD_DIALOGUE', 'TRANSLATE', 'TTS', 'ASSEMBLE', 'MIX', 'SUBTITLE', 'EXPORT',
         'VALIDATE'));

ALTER TABLE media_assets
    DROP CONSTRAINT ck_media_assets_type,
    ADD CONSTRAINT ck_media_assets_type CHECK (asset_type IN
        ('ORIGINAL_VIDEO', 'EXTRACTED_AUDIO', 'AUDIO_CHUNK', 'TRANSCRIPT', 'DIARIZATION', 'SPEAKER_EMBEDDING',
         'TRANSLATION', 'TTS_CLIP', 'VOICE_PREVIEW', 'BACKGROUND_AUDIO', 'DUB_AUDIO', 'MIXED_AUDIO',
         'SUBTITLE', 'EXPORT_VIDEO', 'OTHER',
         'SPEECH_MAP', 'SPEAKER_SAMPLE', 'DIALOGUE', 'DIALOGUE_BACKUP')),
    -- Embeddings and voice samples may only live in their own private folders (separate from each other).
    ADD CONSTRAINT ck_media_assets_private_locations CHECK (
        (asset_type <> 'SPEAKER_EMBEDDING' OR storage_path ~ '^projects/[0-9a-f-]{36}/private/fingerprints/[^/]+$')
        AND (asset_type <> 'SPEAKER_SAMPLE' OR storage_path ~ '^projects/[0-9a-f-]{36}/private/voice-samples/[^/]+$')
        AND (storage_path !~ '/private/' OR asset_type IN ('SPEAKER_EMBEDDING', 'SPEAKER_SAMPLE')));

-- ---------------------------------------------------------------------------
-- processing_profiles: M3a-verified defaults
-- ---------------------------------------------------------------------------
ALTER TABLE processing_profiles
    ADD COLUMN whisper_beam_size        integer      NOT NULL DEFAULT 5,
    ADD COLUMN whisper_cpu_threads      integer      NOT NULL DEFAULT 4,
    ADD COLUMN diarization_threads      integer      NOT NULL DEFAULT 8,
    ADD COLUMN diarization_mode         varchar(10)  NOT NULL DEFAULT 'QUALITY',
    ADD COLUMN diarization_step_quality numeric(4, 3) NOT NULL DEFAULT 0.100,
    ADD COLUMN diarization_step_fast    numeric(4, 3) NOT NULL DEFAULT 0.200,
    ADD COLUMN diarization_chunk_ms     bigint       NOT NULL DEFAULT 600000,
    ADD COLUMN diarization_overlap_ms   bigint       NOT NULL DEFAULT 60000,
    ADD COLUMN speaker_match_threshold  numeric(4, 3) NOT NULL DEFAULT 0.500,
    ADD CONSTRAINT ck_processing_profiles_whisper_beam CHECK (whisper_beam_size BETWEEN 1 AND 10),
    ADD CONSTRAINT ck_processing_profiles_threads CHECK (whisper_cpu_threads BETWEEN 1 AND 16
        AND diarization_threads BETWEEN 1 AND 16),
    ADD CONSTRAINT ck_processing_profiles_diarization_mode CHECK (diarization_mode IN ('QUALITY', 'FAST')),
    ADD CONSTRAINT ck_processing_profiles_diarization_step CHECK (diarization_step_quality > 0
        AND diarization_step_quality <= 1 AND diarization_step_fast > 0 AND diarization_step_fast <= 1),
    ADD CONSTRAINT ck_processing_profiles_diarization_chunk CHECK (diarization_chunk_ms BETWEEN 60000 AND 3600000
        AND diarization_overlap_ms >= 0 AND diarization_overlap_ms < diarization_chunk_ms / 2),
    ADD CONSTRAINT ck_processing_profiles_match_threshold CHECK (speaker_match_threshold > 0
        AND speaker_match_threshold < 2);

-- ---------------------------------------------------------------------------
-- dialogue_revisions
-- ---------------------------------------------------------------------------
CREATE TABLE dialogue_revisions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      uuid         NOT NULL REFERENCES dub_projects (id) ON DELETE CASCADE,
    revision_no     integer      NOT NULL,
    -- BUILDING: being imported; READY: complete, waiting for the user to activate; ACTIVE: the one in use;
    -- SUPERSEDED: replaced but kept (read-only); FAILED: the run failed (the active revision was not touched).
    status          varchar(20)  NOT NULL DEFAULT 'BUILDING',
    source_job_id   uuid REFERENCES processing_jobs (id) ON DELETE SET NULL,
    diarization_mode varchar(10),
    parameters      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    summary         jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- counts only (lines, speakers, flags)
    -- Verified JSON export of the revision that was ACTIVE when this one was activated.
    backup_asset_id uuid REFERENCES media_assets (id) ON DELETE RESTRICT,
    version         bigint       NOT NULL DEFAULT 0,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    activated_at    timestamptz,
    superseded_at   timestamptz,
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_dialogue_revisions_project_no UNIQUE (project_id, revision_no),
    CONSTRAINT ck_dialogue_revisions_no CHECK (revision_no >= 1),
    CONSTRAINT ck_dialogue_revisions_status CHECK (status IN ('BUILDING', 'READY', 'ACTIVE', 'SUPERSEDED', 'FAILED')),
    CONSTRAINT ck_dialogue_revisions_mode CHECK (diarization_mode IS NULL OR diarization_mode IN ('QUALITY', 'FAST')),
    CONSTRAINT ck_dialogue_revisions_activated CHECK (status NOT IN ('ACTIVE', 'SUPERSEDED') OR activated_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_dialogue_revisions_one_active ON dialogue_revisions (project_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_dialogue_revisions_one_building ON dialogue_revisions (project_id) WHERE status = 'BUILDING';
CREATE TRIGGER trg_dialogue_revisions_updated_at BEFORE UPDATE ON dialogue_revisions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- speakers: belong to a revision; review state; history; optimistic locking
-- ---------------------------------------------------------------------------
ALTER TABLE speakers
    DROP CONSTRAINT uq_speakers_project_code,
    ADD COLUMN revision_id     uuid NOT NULL REFERENCES dialogue_revisions (id) ON DELETE CASCADE,
    ADD COLUMN review_status   varchar(20) NOT NULL DEFAULT 'UNREVIEWED',
    ADD COLUMN origin          varchar(20) NOT NULL DEFAULT 'DIARIZATION',
    ADD COLUMN merged_into_id  uuid REFERENCES speakers (id) ON DELETE SET NULL,
    ADD COLUMN speech_ms       bigint  NOT NULL DEFAULT 0,
    ADD COLUMN segment_count   integer NOT NULL DEFAULT 0,
    ADD COLUMN display_order   integer NOT NULL DEFAULT 0,
    ADD COLUMN version         bigint  NOT NULL DEFAULT 0,
    ADD CONSTRAINT uq_speakers_revision_code UNIQUE (revision_id, speaker_code),
    ADD CONSTRAINT ck_speakers_review_status CHECK (review_status IN ('UNREVIEWED', 'REVIEWED')),
    ADD CONSTRAINT ck_speakers_origin CHECK (origin IN ('DIARIZATION', 'SPLIT', 'MANUAL')),
    ADD CONSTRAINT ck_speakers_not_merged_into_self CHECK (merged_into_id IS NULL OR merged_into_id <> id),
    ADD CONSTRAINT ck_speakers_counts CHECK (speech_ms >= 0 AND segment_count >= 0);
CREATE INDEX ix_speakers_revision ON speakers (revision_id, display_order);

-- ---------------------------------------------------------------------------
-- dialogue_segments: belong to a revision; user edits kept apart from AI output
-- ---------------------------------------------------------------------------
ALTER TABLE dialogue_segments
    DROP CONSTRAINT uq_dialogue_segments_project_seq,
    ADD COLUMN revision_id        uuid NOT NULL REFERENCES dialogue_revisions (id) ON DELETE CASCADE,
    ADD COLUMN source_text_edited text,          -- the user's source-language correction; never written by AI
    ADD COLUMN words              jsonb NOT NULL DEFAULT '[]'::jsonb,   -- [{w, s, e, p, retimed?}]
    ADD COLUMN speaker_assignment varchar(10) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN speaker_purity     real,
    ADD COLUMN asr_avg_logprob    real,
    ADD COLUMN flags              jsonb NOT NULL DEFAULT '{}'::jsonb,   -- lowConfidence, mixedSpeakers, firstWordRetimed
    ADD CONSTRAINT uq_dialogue_segments_revision_seq UNIQUE (revision_id, sequence_no) DEFERRABLE INITIALLY IMMEDIATE,
    ADD CONSTRAINT ck_dialogue_segments_speaker_assignment CHECK (speaker_assignment IN ('AUTO', 'MANUAL')),
    ADD CONSTRAINT ck_dialogue_segments_purity CHECK (speaker_purity IS NULL OR speaker_purity BETWEEN 0 AND 1),
    ADD CONSTRAINT ck_dialogue_segments_words_array CHECK (jsonb_typeof(words) = 'array'),
    ADD CONSTRAINT ck_dialogue_segments_flags_object CHECK (jsonb_typeof(flags) = 'object');
CREATE INDEX ix_dialogue_segments_revision_start ON dialogue_segments (revision_id, start_ms);

-- ---------------------------------------------------------------------------
-- glossary_terms (per project; character names, places, terms)
-- ---------------------------------------------------------------------------
CREATE TABLE glossary_terms (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   uuid         NOT NULL REFERENCES dub_projects (id) ON DELETE CASCADE,
    category     varchar(20)  NOT NULL DEFAULT 'CHARACTER',
    source_term  varchar(200) NOT NULL,                       -- canonical spelling, e.g. å²å²
    variants     jsonb        NOT NULL DEFAULT '[]'::jsonb,   -- misrecognised spellings, e.g. ["ç¢Žç¢Ž"]
    khmer_term   varchar(200),
    notes        text,
    speaker_code varchar(50),                                 -- optional link to a character (revision-independent)
    version      bigint       NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_glossary_terms_project_term UNIQUE (project_id, source_term),
    CONSTRAINT ck_glossary_terms_category CHECK (category IN ('CHARACTER', 'PLACE', 'TERM')),
    CONSTRAINT ck_glossary_terms_source CHECK (length(btrim(source_term)) > 0),
    CONSTRAINT ck_glossary_terms_variants CHECK (jsonb_typeof(variants) = 'array')
);
CREATE TRIGGER trg_glossary_terms_updated_at BEFORE UPDATE ON glossary_terms
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ---------------------------------------------------------------------------
-- review_operations (append-only, for undo and audit; never contains embeddings)
-- ---------------------------------------------------------------------------
CREATE TABLE review_operations (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  uuid         NOT NULL REFERENCES dub_projects (id) ON DELETE CASCADE,
    revision_id uuid         NOT NULL REFERENCES dialogue_revisions (id) ON DELETE CASCADE,
    seq         bigserial,
    op_type     varchar(30)  NOT NULL,
    payload     jsonb        NOT NULL,   -- before/after values needed to undo
    undone_at   timestamptz,
    created_at  timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_review_operations_type CHECK (op_type IN
        ('RENAME_SPEAKER', 'UPDATE_SPEAKER', 'MERGE_SPEAKERS', 'SPLIT_SPEAKER', 'CREATE_SPEAKER', 'REASSIGN_SEGMENT',
         'EDIT_SEGMENT', 'SPLIT_SEGMENT', 'MERGE_SEGMENTS', 'ASSIGN_VOICE', 'APPLY_GLOSSARY')),
    CONSTRAINT ck_review_operations_payload CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX ix_review_operations_revision_seq ON review_operations (revision_id, seq);

-- ---------------------------------------------------------------------------
-- privacy_actions (evidence of physical deletion; counts only, never contents)
-- ---------------------------------------------------------------------------
CREATE TABLE privacy_actions (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id     uuid        NOT NULL REFERENCES dub_projects (id) ON DELETE CASCADE,
    action         varchar(30) NOT NULL,
    files_deleted  integer     NOT NULL DEFAULT 0,
    bytes_deleted  bigint      NOT NULL DEFAULT 0,
    verified       boolean     NOT NULL,   -- true only if a re-scan found no remaining files
    details        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_privacy_actions_action CHECK (action IN ('FORGET_FINGERPRINTS', 'DELETE_VOICE_SAMPLES')),
    CONSTRAINT ck_privacy_actions_counts CHECK (files_deleted >= 0 AND bytes_deleted >= 0)
);
CREATE INDEX ix_privacy_actions_project ON privacy_actions (project_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- dub_projects: privacy state
-- ---------------------------------------------------------------------------
ALTER TABLE dub_projects
    ADD COLUMN voice_fingerprints_forgotten_at timestamptz,
    ADD COLUMN voice_samples_deleted_at        timestamptz;

-- ---------------------------------------------------------------------------
-- processing_attempts: when the reported progress last changed (keep-awake stall detection)
-- ---------------------------------------------------------------------------
ALTER TABLE processing_attempts
    ADD COLUMN progress_changed_at timestamptz;
