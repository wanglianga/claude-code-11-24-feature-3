package cc.royalty.domain;

public record HoldBalance(Long id, Long settlementBatchId, Long reportLineId, String rightType,
                          String balanceType, long amountMinor, String currency, String reason,
                          boolean resolved, String resolutionNote, long frozenMinor) {
}
