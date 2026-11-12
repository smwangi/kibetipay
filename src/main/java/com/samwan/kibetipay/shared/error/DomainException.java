package com.samwan.kibetipay.shared.error;

/**
 * Base for business-rule failures. Each subclass maps to one HTTP status and a stable
 * machine-readable {@code code} that clients can switch on (exposed in ProblemDetail).
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 404 */
    public static class NotFound extends DomainException {
        public NotFound(String code, String message) {
            super(code, message);
        }
    }

    /** 409: state conflicts (duplicate wallet, transfer not in expected state). */
    public static class Conflict extends DomainException {
        public Conflict(String code, String message) {
            super(code, message);
        }
    }

    /** 422: request is well-formed but violates a business rule. */
    public static class RuleViolation extends DomainException {
        public RuleViolation(String code, String message) {
            super(code, message);
        }
    }
}
