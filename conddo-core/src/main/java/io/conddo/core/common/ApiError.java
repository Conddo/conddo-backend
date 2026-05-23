package io.conddo.core.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Error body for the standard API envelope (PRD §13.2):
 * <pre>
 * { "code": "VALIDATION_ERROR", "message": "...", "details": [ ... ] }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<FieldError> details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError of(String code, String message, List<FieldError> details) {
        return new ApiError(code, message, details);
    }

    /** A single field-level validation problem. */
    public record FieldError(String field, String message) {
    }
}
