package cc.royalty.domain;

import java.time.OffsetDateTime;

/** 托管账户：按币种独立的资金台账，余额完全由 escrow_entry 分录驱动 */
public record EscrowAccount(Long id, String accountNo, String currency, String title,
                            String status, OffsetDateTime createdAt) {
}
