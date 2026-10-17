package org.example.wardrobe.memory.web;

import org.example.wardrobe.memory.domain.ErrorCode;
import org.example.wardrobe.memory.domain.MemoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;

/** Код ошибки → HTTP по категории: VAL 400, NF 404, STATE 409, прочее 500 {@code MEM-INT-001}. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MemoryException.class)
    ResponseEntity<ApiError> handleMemory(MemoryException e) {
        HttpStatus status = switch (e.code().category()) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case EXTRACTOR, INTERNAL -> null;
        };
        if (status == null) {
            return internal(e);
        }
        return ResponseEntity.status(status).body(new ApiError(e.code().code(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException e) {
        return malformed(cause(e));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return malformed(e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.VAL_011.code(), e.getParameterName() + " is required"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiError> handleOther(RuntimeException e) {
        return internal(e);
    }

    private static ResponseEntity<ApiError> malformed(String detail) {
        return ResponseEntity.badRequest()
                .body(new ApiError(ErrorCode.VAL_015.code(), "request is malformed: " + detail));
    }

    private static ResponseEntity<ApiError> internal(Exception e) {
        log.error("request failed code={}", ErrorCode.INT_001.code(), e);
        return ResponseEntity.internalServerError().body(new ApiError(ErrorCode.INT_001.code(), "internal error"));
    }

    /** Поле, на котором споткнулся разбор тела, иначе — что тело не JSON. Текст тела в ответ не попадает. */
    private static String cause(HttpMessageNotReadableException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
            String field = jackson.getPath().get(jackson.getPath().size() - 1).getPropertyName();
            if (field != null) {
                return field;
            }
        }
        return "body is not valid JSON";
    }
}
