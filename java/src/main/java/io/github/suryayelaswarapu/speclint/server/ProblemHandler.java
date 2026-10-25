package io.github.suryayelaswarapu.speclint.server;

import io.github.suryayelaswarapu.speclint.SpecLintException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps errors to RFC 9457 Problem Details. Never echoes stack traces or submitted content. */
@RestControllerAdvice
public class ProblemHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);
    static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    static ResponseEntity<Map<String, Object>> problem(HttpStatus status, String title,
            String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        if (detail != null) {
            body.put("detail", detail);
        }
        return ResponseEntity.status(status).contentType(PROBLEM).body(body);
    }

    @ExceptionHandler(SpecLintException.SecurityException.class)
    ResponseEntity<Map<String, Object>> unsafe(SpecLintException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Unsafe input rejected", e.getMessage());
    }

    @ExceptionHandler(SpecLintException.class)
    ResponseEntity<Map<String, Object>> invalid(SpecLintException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid input", e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof BodyLimitFilter.PayloadTooLargeException tooLarge) {
                return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large",
                        tooLarge.getMessage());
            }
        }
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "Request body must be a JSON object with 'spec'");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "Request body must be a JSON object with 'spec'");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        LOG.error("Unhandled error", e); // the submitted contract is never logged
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "Unexpected error");
    }
}
