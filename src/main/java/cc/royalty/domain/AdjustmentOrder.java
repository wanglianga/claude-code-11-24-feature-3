package cc.royalty.domain;

public record AdjustmentOrder(Long id, String adjustmentNo, Long sourceSettlementBatchId,
                              Long newSettlementBatchId, String reason, String status) {
}
