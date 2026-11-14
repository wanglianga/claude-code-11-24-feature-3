package cc.royalty.domain;

import java.time.OffsetDateTime;

public record PlatformMapping(Long id, Long platformId, String platformItemCode, Long recordingId,
                              String status, Long adoptedCandidateId, String approvedBy,
                              OffsetDateTime approvedAt, OffsetDateTime revokedAt, String revokeReason) {
}
