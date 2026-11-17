package com.abhishek.attendance.web;

import com.abhishek.attendance.service.SyncService;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, HandlerMethodValidationException.class})
    public ProblemDetail badRequest(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail.isEmpty() ? "Invalid request" : detail);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        String cause = e.getMostSpecificCause().getMessage();
        if (cause != null && cause.length() > 200) cause = cause.substring(0, 200) + "...";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request could not be read: " + cause);
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail duplicate(DuplicateKeyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A record with the same unique value already exists.");
    }

    @ExceptionHandler(SyncService.SyncDisabledException.class)
    public ProblemDetail disabled(SyncService.SyncDisabledException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(SyncService.SyncInProgressException.class)
    public ProblemDetail syncConflict(SyncService.SyncInProgressException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
