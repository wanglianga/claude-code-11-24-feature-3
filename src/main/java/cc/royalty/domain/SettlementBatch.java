package cc.royalty.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record SettlementBatch(Long id, String batchNo, String batchType, Long platformId,
                              LocalDate periodStart, LocalDate periodEnd, String status,
                              String contractSnapshotFingerprint, String reportScope,
                              String lockedBy, String approvedBy, OffsetDateTime approvedAt,
                              String note, OffsetDateTime createdAt, OffsetDateTime calculatedAt) {
}
