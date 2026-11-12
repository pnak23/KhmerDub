package com.khmerdub.api.project;

/** The furthest pipeline milestone a project has reached. Running and failed work is tracked on jobs. */
public enum ProjectStatus {
    CREATED, IMPORTED, TRANSCRIBED, TRANSLATED, VOICED, EXPORTED, ARCHIVED
}
