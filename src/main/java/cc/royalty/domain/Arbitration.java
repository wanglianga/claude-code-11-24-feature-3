package cc.royalty.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 法务裁决：只覆盖声明的子范围（地区/时段），处置方式为
 * RELEASE（释放给原权利人）、TRANSFER（转付受益人）、RETAIN（继续保留在托管）。
 */
public record Arbitration(Long id, String arbitrationNo, long disputeId, long recordingId,
                          String rightType, List<String> territories, LocalDate periodStart,
                          LocalDate periodEnd, String outcome, Long beneficiaryPayeeId,
                          String legalBasis, String note, String status, OffsetDateTime revokedAt,
                          String revokeReason, String createdBy, OffsetDateTime createdAt) {

    public boolean isEffective() {
        return "EFFECTIVE".equals(status);
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
