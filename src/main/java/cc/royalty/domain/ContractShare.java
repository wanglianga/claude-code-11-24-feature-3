package cc.royalty.domain;

public record ContractShare(Long id, Long contractId, Long payeeId, String role,
                            long shareNumerator, long shareDenominator) {
}
