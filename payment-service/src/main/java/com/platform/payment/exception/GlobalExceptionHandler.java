package com.platform.payment.exception;

import com.platform.common.error.ProblemDetails;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ProblemDetails> handleNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.warn("Payment not found: {} (correlationId: {})", ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Payment Not Found",
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<ProblemDetails> handleDeclined(PaymentDeclinedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.warn("Payment declined: [{}] {} (correlationId: {})", ex.getErrorCode(), ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Payment Declined",
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ProblemDetails> handleCircuitBreakerOpen(CallNotPermittedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.error("Resilience4j Circuit Breaker is OPEN: {} (correlationId: {})", ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Service Unavailable",
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "The upstream payment provider is temporarily unavailable due to high error rates. Circuit breaker is OPEN.",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetails> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.error("Payment constraint conflict: {}", ex.getMessage());
        ProblemDetails problem = ProblemDetails.of(
                "Payment Idempotency Conflict",
                HttpStatus.CONFLICT.value(),
                "A payment for this order or idempotency key already exists.",
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

        ProblemDetails problem = ProblemDetails.withValidationErrors(
                "Validation Failed",
                HttpStatus.BAD_REQUEST.value(),
                "Payment request parameter validation failed.",
                request.getRequestURI(),
                correlationId,
                invalidParams
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetails> handleGeneric(Exception ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.error("Internal error in payment service (correlationId: {})", correlationId, ex);
        ProblemDetails problem = ProblemDetails.of(
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected internal error occurred in payment-service.",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
