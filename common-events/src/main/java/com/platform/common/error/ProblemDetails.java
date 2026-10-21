package com.platform.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * RFC 7807 Problem Details for HTTP APIs.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemDetails(
        URI type,
        String title,
        int status,
        String detail,
        URI instance,
        Instant timestamp,
        String correlationId,
        List<InvalidParam> invalidParams
) {
    public static ProblemDetails of(String title, int status, String detail, String instancePath, String correlationId) {
        return new ProblemDetails(
                URI.create("https://api.platform.com/errors/" + title.toLowerCase().replace(' ', '-')),
                title,
                status,
                detail,
                URI.create(instancePath),
                Instant.now(),
                correlationId,
                null
        );
    }

    public static ProblemDetails withValidationErrors(
            String title,
            int status,
            String detail,
            String instancePath,
            String correlationId,
            List<InvalidParam> invalidParams
    ) {
        return new ProblemDetails(
                URI.create("https://api.platform.com/errors/validation-error"),
                title,
                status,
                detail,
                URI.create(instancePath),
                Instant.now(),
                correlationId,
                invalidParams
        );
    }

    public record InvalidParam(String field, Object rejectedValue, String reason) {}
}
