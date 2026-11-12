package com.samwan.kibetipay.lending.internal;

import com.samwan.kibetipay.shared.error.DomainException;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

interface LoanRepository extends JpaRepository<Loan, UUID> {

    Optional<Loan> findByAgentIdAndIdempotencyKey(UUID agentId, String idempotencyKey);

    /** Serialises concurrent repayments on the same loan (wallet + agent cash at the same time). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id")
    Optional<Loan> findForUpdate(@Param("id") UUID id);

    @Query("select l.id from Loan l where l.status = :status")
    List<UUID> findIdsByStatus(@Param("status") Loan.Status status);
}

@Component
class ProductCatalog {

    private final JdbcClient jdbc;

    ProductCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Product require(String code) {
        return jdbc.sql("SELECT * FROM lending_product WHERE code = :c")
                .param("c", code)
                .query(Product.class)
                .optional()
                .orElseThrow(() -> new DomainException.NotFound("product_not_found", "No loan product " + code));
    }

    List<Product> active() {
        return jdbc.sql("SELECT * FROM lending_product WHERE active ORDER BY min_principal_minor")
                .query(Product.class).list();
    }
}
