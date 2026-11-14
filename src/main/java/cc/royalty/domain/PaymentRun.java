package cc.royalty.domain;

import java.time.OffsetDateTime;

public record PaymentRun(Long id, String runNo, Long settlementBatchId, String currency,
                         String status, String createdBy, OffsetDateTime createdAt,
                         String runKind, Long accountId) {
}
