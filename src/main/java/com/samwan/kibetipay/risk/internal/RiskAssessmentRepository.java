package com.samwan.kibetipay.risk.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, UUID> {

    Optional<RiskAssessment> findByTransactionId(UUID transactionId);

    long countBySubjectIdAndCreatedAtAfter(String subjectId, Instant since);

    long countBySubjectId(String subjectId);
}
