package cc.royalty.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record ReportBatch(Long id, String batchRef, Long platformId, LocalDate periodStart,
                          LocalDate periodEnd, String sourceFile, Long declaredTotalCount,
                          Long declaredGrossMinor, Long declaredFeeMinor, Long declaredNetMinor,
                          String currency, String status, int parseValidCount, int parseBadCount,
                          int parseDupCount, String reconciliation, String note,
                          OffsetDateTime receivedAt) {
}
