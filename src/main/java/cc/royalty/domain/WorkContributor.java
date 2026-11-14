package cc.royalty.domain;

public record WorkContributor(Long id, Long workId, Long payeeId, String contributorName, String role) {
}
