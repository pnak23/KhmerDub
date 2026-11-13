package com.khmerdub.api.job;

/** Outcomes of the worker protocol that are not normal success. */
public final class EngineExceptions {

    private EngineExceptions() {
    }

    /** The attempt is not (or no longer) allowed to act. The worker must stop and publish nothing. HTTP 409. */
    public static final class FencedException extends RuntimeException {
        public FencedException(String message) {
            super(message);
        }
    }

    /** No such attempt. HTTP 404. */
    public static final class AttemptNotFoundException extends RuntimeException {
        public AttemptNotFoundException(java.util.UUID id) {
            super("Unknown attempt " + id);
        }
    }

    /** The worker reported a result that cannot be accepted (missing file, wrong size or hash). HTTP 422. */
    public static final class InvalidOutputException extends RuntimeException {
        public InvalidOutputException(String message) {
            super(message);
        }
    }

    /** The work succeeded, but its result means the job cannot continue (e.g. the movie has no audio). */
    public static final class JobCannotContinueException extends RuntimeException {
        public JobCannotContinueException(String message) {
            super(message);
        }
    }

    /** A stage could not be planned (missing prerequisite). */
    public static final class PlanningException extends RuntimeException {
        public PlanningException(String message) {
            super(message);
        }
    }
}
