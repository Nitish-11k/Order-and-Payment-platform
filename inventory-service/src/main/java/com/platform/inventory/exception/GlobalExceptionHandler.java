package com.platform.inventory.exception;

import com.platform.common.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetails> handleProductNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.warn("Product not found: {} (correlationId: {})", ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Product Not Found",
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ProblemDetails> handleInsufficientStock(InsufficientStockException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.warn("Stock reservation conflict: {} (correlationId: {})", ex.getMessage(), correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Insufficient Stock",
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
        log.warn("Optimistic lock failure during inventory update (correlationId: {})", correlationId);
        ProblemDetails problem = ProblemDetails.of(
                "Inventory Concurrency Conflict",
                HttpStatus.CONFLICT.value(),
                "Inventory was modified by a concurrent purchase. Please retry the request.",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetails> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.error("Database constraint error: {}", ex.getMessage());
        ProblemDetails problem = ProblemDetails.of(
                "Database Constraint Violation",
                HttpStatus.CONFLICT.value(),
                "Stock quantity constraint violated (e.g. quantity cannot be negative).",
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
                "Invalid parameters in inventory request.",
                request.getRequestURI(),
                correlationId,
                invalidParams
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetails> handleGeneric(Exception ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        log.error("Internal error in inventory service (correlationId: {})", correlationId, ex);
        ProblemDetails problem = ProblemDetails.of(
                "Internal Server Error",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected internal error occurred in inventory-service.",
                request.getRequestURI(),
                correlationId
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }
}
