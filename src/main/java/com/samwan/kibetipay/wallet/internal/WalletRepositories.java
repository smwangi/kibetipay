package com.samwan.kibetipay.wallet.internal;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WalletAccountRepository extends JpaRepository<WalletAccount, UUID> {

    Optional<WalletAccount> findByOwnerIdAndCurrency(String ownerId, String currency);

    boolean existsByOwnerIdAndCurrency(String ownerId, String currency);

    /**
     * SELECT ... FOR UPDATE, ordered by id. Locking every wallet in a posting in the same
     * global order makes deadlocks impossible when A pays B while B pays A.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletAccount w where w.ownerId in :owners and w.currency = :currency order by w.id")
    List<WalletAccount> lockAllByOwners(@Param("owners") Collection<String> owners,
                                        @Param("currency") String currency);
}

interface JournalRepository extends JpaRepository<Journal, UUID> {

    Optional<Journal> findByReference(String reference);
}
