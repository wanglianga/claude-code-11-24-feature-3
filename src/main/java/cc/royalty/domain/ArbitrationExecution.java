package cc.royalty.domain;

import java.time.OffsetDateTime;

/** 裁决执行批次：部分失败保留进度，可按 attempt 重复执行，已执行行不重复付款 */
public record ArbitrationExecution(Long id, long arbitrationId, int attemptSeq, String status,
                                   String actor, String note, OffsetDateTime createdAt) {
}
