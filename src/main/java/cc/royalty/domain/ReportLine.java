package cc.royalty.domain;

import java.time.LocalDate;

public record ReportLine(Long id, Long batchId, String platformLineNo, int revisionNo,
                         Long supersedesLineId, String platformItemCode, String reportedTitle,
                         String reportedArtist, Integer reportedDuration, LocalDate usageDate,
                         String territory, String media, Long usageCount, Long grossMinor,
                         Long feeMinor, Long netMinor, String currency, String rawText,
                         String parseStatus, String rejectReason, String usageStatus,
                         Long supersededByLineId) {
    public boolean active() {
        return "VALID".equals(parseStatus) && "NEW".equals(usageStatus);
    }
}
