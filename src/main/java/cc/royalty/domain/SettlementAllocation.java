package cc.royalty.domain;

public record SettlementAllocation(Long id, Long settlementLineId, Long payeeId, Long contractId,
                                   String allocStatus, long shareNumerator, long shareDenominator,
                                   long amountMinor, long frozenMinor, long clawedMinor) {
}
