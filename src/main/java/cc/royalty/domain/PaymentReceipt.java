package cc.royalty.domain;

public record PaymentReceipt(Long id, String receiptRef, String requestIdRef, String accountRef,
                             long amountMinor, String currency, String sourceFile,
                             String matchStatus, String mismatchReason) {
}
