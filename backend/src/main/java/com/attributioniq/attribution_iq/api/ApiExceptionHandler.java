package com.attributioniq.attribution_iq.api;

import com.attributioniq.attribution_iq.database.RejectedRequestRepository;
import com.attributioniq.attribution_iq.api.filter.RequestTracingFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final RejectedRequestRepository rejects;

    public ApiExceptionHandler(RejectedRequestRepository rejects) {
        this.rejects = rejects;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @SuppressWarnings("unused")
    public ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return problem("Request validation failed", errors, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    @SuppressWarnings("unused")
    public ProblemDetail handleInvalidRequest(Exception exception, HttpServletRequest request) {
        String detail = exception instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for parameter '" + mismatch.getName() + "'"
                : "Request body or parameter contains an invalid value";
        return problem("Invalid request", detail, request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    @SuppressWarnings("unused")
    public ProblemDetail handleStatusException(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        log.warn("Request rejected path={} status={} reason={}", request.getRequestURI(), status.value(),
                exception.getReason());
        return problem(status, "Request rejected", exception.getReason() == null
                ? "The request could not be processed" : exception.getReason(), request);
    }

    private ProblemDetail problem(String title, String detail, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, title, detail, request);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
                                  HttpServletRequest request) {
        String requestId = MDC.get(RequestTracingFilter.MDC_REQUEST_ID_KEY);
        rejects.record(request.getRequestURI(), detail, request.getMethod(),
                request.getContentType(), requestId);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("about:blank"));
        problem.setInstance(URI.create(request.getRequestURI()));
        if (requestId != null) problem.setProperty("requestId", requestId);
        return problem;
    }
}
