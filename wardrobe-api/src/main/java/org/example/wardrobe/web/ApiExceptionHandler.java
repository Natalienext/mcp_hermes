package org.example.wardrobe.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> handleInvalidFilter(MethodArgumentTypeMismatchException e) {
        return Map.of("error", "Invalid parameter '" + e.getName() + "': " + e.getMostSpecificCause().getMessage());
    }
}
