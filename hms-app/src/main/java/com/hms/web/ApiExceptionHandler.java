package com.hms.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/** Turns every failure into the same JSON shape: {@code {"error": "..."}}. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static ResponseEntity<Object> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> handleApi(ApiException e) {
        return error(e.status(), e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Object> handleDuplicate(DuplicateKeyException e) {
        return error(HttpStatus.CONFLICT, "That record already exists");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e) {
        log.error("Unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    /** Spring's own request errors (bad JSON, missing parameter, failed validation, wrong method...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String message = "Request could not be processed";
        if (ex instanceof MethodArgumentNotValidException invalid && invalid.getBindingResult().hasFieldErrors()) {
            FieldError field = invalid.getBindingResult().getFieldErrors().get(0);
            message = field.getField() + ": " + field.getDefaultMessage();
        } else if (status.is4xxClientError()) {
            HttpStatus resolved = HttpStatus.resolve(status.value());
            message = resolved != null ? resolved.getReasonPhrase() : message;
        }
        return ResponseEntity.status(status).headers(headers).body(Map.of("error", message));
    }
}
