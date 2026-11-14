package cc.royalty.domain;

import java.time.LocalDate;

public record SoundRecording(Long id, String recordingCode, Long workId, String versionTitle,
                             Integer durationSeconds, Long ownerPayeeId,
                             LocalDate firstReleaseDate, String status) {
}
