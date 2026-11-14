package cc.royalty.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 最小冻结单元：一个结算分摊行被冻结的部分金额。
 * 重叠争议只在 escrow_freeze_cover 上求并集，同一分摊行全系统至多一条冻结，不能同一份额冻结两次。
 */
public record EscrowFreeze(Long id, long accountId, long settlementAllocationId, Long holdBalanceId,
                           long settlementBatchId, long reportLineId, long recordingId,
                           String rightType, Long payeeId, String territory, LocalDate usageDate,
                           String currency, long originalAmountMinor, long frozenAmountMinor,
                           List<Long> disputeIds, String status, long releasedMinor,
                           long transferredMinor, OffsetDateTime createdAt) {

    public long remainingMinor() {
        return frozenAmountMinor - releasedMinor - transferredMinor;
    }

    public boolean isOpen() {
        return remainingMinor() > 0;
    }

    public static List<Long> parseDisputeIds(String raw) {
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::parseLong).toList();
    }

    public static String joinDisputeIds(List<Long> ids) {
        return ids.stream().distinct().sorted().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
    }
}
