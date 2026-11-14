package cc.royalty.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SettlementLine(Long id, Long settlementBatchId, Long reportLineId, String rightType,
                             Long recordingId, Long workId, LocalDate usageDate, String territory,
                             String media, String allocationScope, long distributableMinor,
                             long feeMinor, String basis, BigDecimal rateUsed,
                             String chosenContractIds, boolean conflictFlag, String conflictDetail,
                             String status, String holdReason, String sourceFingerprint, int segmentNo) {
}
