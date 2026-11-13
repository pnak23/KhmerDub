package com.khmerdub.api.project;

/**
 * The furthest pipeline milestone a project has reached. Running and failed work is tracked on jobs;
 * archiving is a separate flag ({@code archived_at}).
 */
public enum ProjectStatus {
    CREATED, IMPORTED, AUDIO_READY, TRANSCRIBED, TRANSLATED, VOICED, EXPORTED;

    public boolean atLeast(ProjectStatus other) {
        return ordinal() >= other.ordinal();
    }
}
