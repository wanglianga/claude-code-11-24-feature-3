package cc.royalty.domain;

public record Payable(Long id, Long settlementBatchId, Long payeeId, String rightType,
                      String currency, long amountMinor, long paidMinor, String status,
                      long frozenMinor, long frozenReleaseMinor, String sourceKind,
                      Long arbitrationId) {
    public long outstanding() {
        // 被托管冻结的金额不进入普通待付；托管释放形成的应付走托管付款
        return amountMinor - paidMinor - frozenMinor;
    }
}
