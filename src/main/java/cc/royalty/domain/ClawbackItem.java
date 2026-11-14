package cc.royalty.domain;

import java.time.OffsetDateTime;

/**
 * 追回事项：争议声明时该笔款已经付出（payable 已确认），
 * 不属于托管现存资金，只能生成追回事项另行回收。
 */
public record ClawbackItem(Long id, String clawbackNo, long disputeId, long settlementBatchId,
                           long payableId, long payeeId, String currency, long amountMinor,
                           String status, String reason, OffsetDateTime recoveredAt,
                           String recoveryRef, OffsetDateTime createdAt) {
}
