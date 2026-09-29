package com.gleidsonfersanp.observability.api;

import com.gleidsonfersanp.observability.shared.BusinessException;
import com.gleidsonfersanp.observability.shared.IntegrationBadRequestException;
import com.gleidsonfersanp.observability.shared.IntegrationServerException;
import com.gleidsonfersanp.observability.shared.IntegrationThrottledException;
import com.gleidsonfersanp.observability.shared.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, String>> handleBusinessException(BusinessException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not Found", "message", ex.getMessage()));
    }

    @ExceptionHandler(IntegrationBadRequestException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IntegrationBadRequestException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "Bad Request", "message", ex.getMessage()));
    }

    @ExceptionHandler(IntegrationThrottledException.class)
    public ResponseEntity<Map<String, String>> handleThrottled(IntegrationThrottledException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Too Many Requests", "message", ex.getMessage()));
    }

    @ExceptionHandler(IntegrationServerException.class)
    public ResponseEntity<Map<String, String>> handleServerError(IntegrationServerException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Bad Gateway", "message", ex.getMessage()));
    }
}
