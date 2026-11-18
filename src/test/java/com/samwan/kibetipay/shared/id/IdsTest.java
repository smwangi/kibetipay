package com.samwan.kibetipay.shared.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void newIdIsAnRfc9562Version7Uuid() {
        UUID id = Ids.newId();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void newIdEmbedsTheCreationTimeInItsFirst48Bits() {
        long before = System.currentTimeMillis();
        UUID id = Ids.newId();
        long after = System.currentTimeMillis();
        assertThat(id.getMostSignificantBits() >>> 16).isBetween(before, after);
    }

    @Test
    void idsFromLaterMillisecondsSortAfterEarlierOnes() {
        UUID earlier = Ids.uuidV7(1_700_000_000_000L, -1L, -1L);
        UUID later = Ids.uuidV7(1_700_000_000_001L, 0L, 0L);
        // Postgres compares uuid bytes as unsigned, which matches the string order.
        assertThat(later.toString()).isGreaterThan(earlier.toString());
    }

    @Test
    void layoutKeepsVersionAndVariantWhateverTheRandomBits() {
        UUID id = Ids.uuidV7(0x0123_4567_89ABL, -1L, -1L);
        assertThat(id.toString()).startsWith("01234567-89ab-7fff-bfff-");
    }

    @Test
    void txnRefIsPrefixedCrockfordBase32() {
        String ref = Ids.newTxnRef();
        assertThat(ref).hasSize(Ids.TXN_REF_LENGTH).startsWith("KP").doesNotContainPattern("[ILOU]");
        assertThat(Ids.isTxnRef(ref)).isTrue();
    }

    @Test
    void txnRefsDoNotRepeatInPractice() {
        var seen = new HashSet<String>();
        for (int i = 0; i < 100_000; i++) {
            assertThat(seen.add(Ids.newTxnRef())).isTrue();
        }
    }

    @Test
    void rejectsMalformedTxnRefs() {
        assertThat(Ids.isTxnRef(null)).isFalse();
        assertThat(Ids.isTxnRef("KP123")).isFalse();
        assertThat(Ids.isTxnRef("kp7h3qx9m2ab")).isFalse();
        assertThat(Ids.isTxnRef("KP7H3QX9M2AI")).isFalse();
        assertThat(Ids.isTxnRef("XX7H3QX9M2AB")).isFalse();
    }
}
