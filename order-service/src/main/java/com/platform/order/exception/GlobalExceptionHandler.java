package com.platform.order.exception;

import com.platform.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private String resolveCorrelationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-Id");
        return correlationId != null ? correlationId : UUID.randomUUID().toString();
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ProblemDetails> handleNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.warn("Order not found: {} (correlationId: {})", ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Order Not Found",
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(DuplicateIdempotencyKeyException.class)
    public ResponseEntity<ProblemDetails> handleDuplicateIdempotency(DuplicateIdempotencyKeyException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.warn("Idempotency conflict: {} (correlationId: {})", ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Idempotency Conflict",
                HttpStatus.CONFLICT.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetails> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.warn("Optimistic lock conflict: {} (correlationId: {})", ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Concurrent Modification Conflict",
                HttpStatus.CONFLICT.value(),
                "The requested resource was modified concurrently by another transaction. Please retry.",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetails> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.error("Data integrity violation: {} (correlationId: {})", ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Data Integrity Violation",
                HttpStatus.CONFLICT.value(),
                "A database constraint violation occurred (e.g. unique constraint or foreign key).",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetails> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        List<ProblemDetails.InvalidParam> invalidParams = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> new ProblemDetails.InvalidParam(err.getField(), err.getRejectedValue(), err.getDefaultMessage()))
                .toList();

        log.warn("Validation failed for URI: {} with {} errors", request.getRequestURI(), invalidParams.size());
        ProblemDetails problem = ProblemDetails.withValidationErrors(
                "Validation Failed",
                HttpStatus.BAD_REQUEST.value(),
                "One or more request parameters failed validation.",
                request.getRequestURI(),
                correlationId,
                invalidParams
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetails> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ProblemDetails problem = ProblemDetails.of(
                "Missing Required Header",
                HttpStatus.BAD_REQUEST.value(),
                "Required request header '" + ex.getHeaderName() + "' is missing.",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetails> handleGeneric(Exception ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.error("Unhandled server exception (correlationId: {})", correlationId, ex);
        ProblemDetails problem = ProblemDetails.of(
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected internal error occurred. Please contact support with the correlation ID.",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
