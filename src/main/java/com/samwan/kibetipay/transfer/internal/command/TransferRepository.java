package com.samwan.kibetipay.transfer.internal.command;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByInitiatorIdAndIdempotencyKey(String initiatorId, String idempotencyKey);
}
