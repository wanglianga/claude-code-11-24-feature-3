package cc.royalty.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 争议案件：法务以 录音 + 权利类别 限定争议，再声明适用地区、使用时段与争议比例。
 * 争议不直接等于冻结金额——实际冻结范围由争议声明与结算项求交得出。
 */
public record DisputeCase(Long id, String disputeNo, long recordingId, String rightType,
                          List<String> territories, LocalDate periodStart, LocalDate periodEnd,
                          long ratioNumerator, long ratioDenominator,
                          Long claimantPayeeId, Long respondentPayeeId, String legalRef,
                          String reason, String status, OffsetDateTime withdrawnAt,
                          String withdrawReason, String createdBy, OffsetDateTime createdAt) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean coversTerritory(String territory) {
        return territories.contains("GLOBAL") || territories.contains(territory);
    }

    public boolean coversDate(LocalDate date) {
        return date != null && !date.isBefore(periodStart) && !date.isAfter(periodEnd);
    }

    public static List<String> parseTerritories(String raw) {
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
