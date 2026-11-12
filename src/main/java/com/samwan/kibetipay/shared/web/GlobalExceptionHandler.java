package com.samwan.kibetipay.shared.web;

import com.samwan.kibetipay.shared.error.DomainException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * RFC 9457 Problem Details for every error. Validation errors are handled by the
 * parent class; business errors carry a stable {@code code} property.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.NotFound.class)
    ProblemDetail notFound(DomainException ex) {
        return problem(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(DomainException.Conflict.class)
    ProblemDetail conflict(DomainException ex) {
        return problem(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(DomainException.RuleViolation.class)
    ProblemDetail ruleViolation(DomainException ex) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ex);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail optimisticLock(OptimisticLockingFailureException ex) {
        log.info("Optimistic lock conflict: {}", ex.getMessage());
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource was modified concurrently. Retry the request.");
        pd.setProperty("code", "concurrent_modification");
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, DomainException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        pd.setType(URI.create("https://kibetipay.dev/errors/" + ex.code()));
        pd.setProperty("code", ex.code());
        return pd;
    }
}
