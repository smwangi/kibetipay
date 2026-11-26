package com.samwan.kibetipay.shared.paging;

import com.samwan.kibetipay.shared.error.DomainException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset position in a newest-first list ordered by {@code (created_at DESC, id DESC)}. The next
 * page is everything strictly older than this row, so new inserts never shift or duplicate rows
 * the way OFFSET paging does.
 *
 * <p>Opaque to clients (base64url). It is a position only, never authorisation: queries still
 * filter by the caller, so a forged cursor can at most skip rows of the caller's own history.
 */
public record Cursor(Instant at, UUID id) {

    public String encode() {
        String raw = at.getEpochSecond() + "." + at.getNano() + "." + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code null} or blank means "from the newest row". */
    public static Cursor decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\.", 3);
            return new Cursor(Instant.ofEpochSecond(Long.parseLong(parts[0]), Long.parseLong(parts[1])),
                    UUID.fromString(parts[2]));
        } catch (RuntimeException e) {
            throw new DomainException.RuleViolation("invalid_cursor", "The paging cursor is not valid");
        }
    }
}
