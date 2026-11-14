package cc.royalty.domain;

import java.time.OffsetDateTime;

/**
 * 托管台账分录（不可变）。
 * FREEZE_IN 记进入；RELEASE/TRANSFER_OUT 记裁决释放/转付或争议撤回自动解冻；
 * RELEASE_REVOKE 记裁决撤回后把未付款项退回托管；CLAWBACK_ADJ/MANUAL_ADJ 为追回到账/人工调整。
 * 恒等式：托管入账 = 可支配余额 + 待付 + 已释放净额（余额不含已裁决待付部分）。
 */
public record EscrowEntry(Long id, long accountId, long entrySeq, String entryType,
                          long amountMinor, String currency, Long escrowFreezeId, Long disputeId,
                          Long arbitrationId, Long paymentRequestId, Long counterpartyPayeeId,
                          String legalRef, String note, long balanceAfterMinor,
                          OffsetDateTime createdAt) {
}
