package com.khmerdub.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** An error with a clear message for the user, returned as RFC 9457 problem details. */
public class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;

    public ApiException(HttpStatusCode status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatusCode status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    public static ApiException unsupportedMedia(String detail) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA", detail);
    }

    public static ApiException tooLarge(String detail) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", detail);
    }

    public static ApiException insufficientStorage(String detail) {
        return new ApiException(HttpStatus.INSUFFICIENT_STORAGE, "INSUFFICIENT_STORAGE", detail);
    }
}
