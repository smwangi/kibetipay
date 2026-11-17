package com.samwan.kibetipay.shared.id;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The single place that decides what identifiers look like.
 *
 * <ul>
 *   <li>{@link #newId()}: internal primary keys. UUIDv7 (RFC 9562): a 48-bit Unix-millisecond
 *       timestamp followed by random bits, so keys are roughly creation-ordered and append to the
 *       end of B-tree indexes instead of landing at random pages like v4. Never shown to customers.</li>
 *   <li>{@link #newTxnRef()}: the short, human-friendly transaction reference printed on receipts,
 *       read out to support and searched by agents. Independent of the primary key.</li>
 * </ul>
 *
 * Deterministic, name-based ids (e.g. derived from a provider reference) are deliberately not
 * produced here; those call sites need {@link UUID#nameUUIDFromBytes(byte[])} semantics.
 */
public final class Ids {

    /** Prefix that marks a reference as a KibetiPay transaction. */
    public static final String TXN_REF_PREFIX = "KP";

    /** Crockford Base32: no I, L, O or U, so references survive being read aloud or hand-typed. */
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /** 10 symbols x 5 bits = 50 random bits; the UNIQUE constraint backs this up. */
    private static final int TXN_REF_RANDOM_CHARS = 10;

    public static final int TXN_REF_LENGTH = TXN_REF_PREFIX.length() + TXN_REF_RANDOM_CHARS;

    private static final Pattern TXN_REF = Pattern.compile(
            TXN_REF_PREFIX + "[0-9A-HJKMNP-TV-Z]{" + TXN_REF_RANDOM_CHARS + "}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    /** A new time-ordered UUIDv7 for use as a primary key. */
    public static UUID newId() {
        return uuidV7(System.currentTimeMillis(), RANDOM.nextLong(), RANDOM.nextLong());
    }

    /** A new customer-facing transaction reference, e.g. {@code KP7H3QX9M2AB}. */
    public static String newTxnRef() {
        var ref = new StringBuilder(TXN_REF_LENGTH).append(TXN_REF_PREFIX);
        for (int i = 0; i < TXN_REF_RANDOM_CHARS; i++) {
            ref.append(CROCKFORD[RANDOM.nextInt(CROCKFORD.length)]);
        }
        return ref.toString();
    }

    /** True for a well-formed reference; normalises nothing, so callers decide on case-folding. */
    public static boolean isTxnRef(String value) {
        return value != null && TXN_REF.matcher(value).matches();
    }

    /** Layout: unix_ts_ms (48) | ver=0111 (4) | rand_a (12) | var=10 (2) | rand_b (62). */
    static UUID uuidV7(long epochMillis, long randA, long randB) {
        long msb = (epochMillis << 16) | 0x7000L | (randA & 0x0FFFL);
        long lsb = (randB & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }
}
