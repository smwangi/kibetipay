package com.samwan.kibetipay.shared.security;

/**
 * The authenticated user for the current request, carried in a {@link ScopedValue} (final in Java 25).
 *
 * <p>Why not ThreadLocal? A scoped value is immutable, bound for a well-defined block (the request)
 * and automatically gone when the block exits, so it cannot leak to the next request on a pooled
 * thread, and it is cheap with millions of virtual threads.
 *
 * <p>It is deliberately NOT inherited by {@code @Async} listeners: background work such as the
 * transfer saga must act on ids stored in the event, never on "whoever happens to be logged in".
 */
public final class CurrentUser {

    static final ScopedValue<String> ID = ScopedValue.newInstance();

    private CurrentUser() {
    }

    /** Id of the authenticated caller. Fails loudly when called outside an authenticated request. */
    public static String id() {
        return ID.orElseThrow(() -> new IllegalStateException("No authenticated user bound to this scope"));
    }

    public static boolean isBound() {
        return ID.isBound();
    }
}
