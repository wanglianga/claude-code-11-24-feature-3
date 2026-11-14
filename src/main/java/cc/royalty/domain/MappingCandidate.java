package cc.royalty.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record MappingCandidate(Long id, Long reportLineId, Long recordingId, BigDecimal score,
                               BigDecimal titleSimilarity, boolean contributorMatch,
                               Integer durationDiffSeconds, String basis, String status,
                               OffsetDateTime decidedAt) {
}
