package cc.royalty.domain;

import java.time.LocalDate;

public record Contract(Long id, String contractCode, Long workId, Long recordingId,
                       String rightType, String territory, String media,
                       LocalDate validFrom, LocalDate validTo, String status) {
}
