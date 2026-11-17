package com.samwan.kibetipay.mobilemoney.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.samwan.kibetipay.mobilemoney.internal.CollectionState.Status;
import com.samwan.kibetipay.shared.error.DomainException;
import org.junit.jupiter.api.Test;

class CollectionStateTest {
    @Test
    void terminalResultsCannotChangeAndIdenticalResultIsAReplay() {
        assertThat(CollectionState.requireResult(Status.PENDING, true)).isTrue();
        assertThat(CollectionState.requireResult(Status.UNKNOWN, false)).isTrue();
        assertThat(CollectionState.requireResult(Status.COMPLETED, true)).isFalse();
        assertThat(CollectionState.requireResult(Status.FAILED, false)).isFalse();
        assertThatThrownBy(() -> CollectionState.requireResult(Status.COMPLETED, false)).isInstanceOf(DomainException.Conflict.class);
        assertThatThrownBy(() -> CollectionState.requireResult(Status.FAILED, true)).isInstanceOf(DomainException.Conflict.class);
        assertThatThrownBy(() -> CollectionState.requireResult(Status.REJECTED, true)).isInstanceOf(DomainException.Conflict.class);
    }
}
