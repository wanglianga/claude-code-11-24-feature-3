package cc.royalty.domain;

import java.time.OffsetDateTime;

public record PaymentRequest(Long id, Long paymentRunId, Long payableId, Long payeeId,
                             String requestId, String accountSnapshot, String bodyFingerprint,
                             long amountMinor, String currency, String status,
                             OffsetDateTime createdAt, OffsetDateTime confirmedAt,
                             Long escrowEntryId, Long arbitrationId) {

    public boolean isEscrowPayment() {
        // 托管付款请求绑定裁决（执行阶段不写分录，故以 arbitrationId 为准）
        return escrowEntryId != null || arbitrationId != null;
    }
}
