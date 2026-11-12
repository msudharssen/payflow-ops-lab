package com.payflow.web;

import com.payflow.service.FraudCheckClient;
import com.payflow.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(PaymentService.NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PaymentService.ValidationException.class)
    public ResponseEntity<Map<String, String>> invalid(PaymentService.ValidationException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> badRequest(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("validation failed");
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(FraudCheckClient.FraudCheckException.class)
    public ResponseEntity<Map<String, String>> fraudDown(FraudCheckClient.FraudCheckException e) {
        log.error("rejecting payment, fraud-check unavailable: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception e) {
        log.error("unhandled error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal error: " + e.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", message,
                "traceId", String.valueOf(MDC.get(TraceIdFilter.TRACE_ID))));
    }
}
