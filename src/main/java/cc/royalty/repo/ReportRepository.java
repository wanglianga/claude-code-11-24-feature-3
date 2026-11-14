package cc.royalty.repo;

import cc.royalty.domain.HoldBalance;
import cc.royalty.domain.MappingCandidate;
import cc.royalty.domain.ReportBatch;
import cc.royalty.domain.ReportLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ReportRepository {

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReportBatch> BATCH_MAPPER = (rs, n) -> new ReportBatch(
            rs.getLong("id"), rs.getString("batch_ref"), rs.getLong("platform_id"),
            rs.getDate("period_start").toLocalDate(), rs.getDate("period_end").toLocalDate(),
            rs.getString("source_file"), (Long) rs.getObject("declared_total_count"),
            (Long) rs.getObject("declared_gross_minor"), (Long) rs.getObject("declared_fee_minor"),
            (Long) rs.getObject("declared_net_minor"), rs.getString("currency"), rs.getString("status"),
            rs.getInt("parse_valid_count"), rs.getInt("parse_bad_count"), rs.getInt("parse_dup_count"),
            rs.getString("reconciliation"), rs.getString("note"),
            rs.getObject("received_at", java.time.OffsetDateTime.class));

    private static final RowMapper<ReportLine> LINE_MAPPER = (rs, n) -> new ReportLine(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("platform_line_no"),
            rs.getInt("revision_no"), (Long) rs.getObject("supersedes_line_id"),
            rs.getString("platform_item_code"), rs.getString("reported_title"),
            rs.getString("reported_artist"), (Integer) rs.getObject("reported_duration"),
            rs.getDate("usage_date").toLocalDate(),
            rs.getString("territory"), rs.getString("media"), (Long) rs.getObject("usage_count"),
            rs.getLong("gross_minor"), (Long) rs.getObject("fee_minor"),
            rs.getLong("net_minor"), rs.getString("currency"), rs.getString("raw_text"),
            rs.getString("parse_status"), rs.getString("reject_reason"), rs.getString("usage_status"),
            (Long) rs.getObject("superseded_by_line_id"));

    private static final RowMapper<MappingCandidate> CANDIDATE_MAPPER = (rs, n) -> new MappingCandidate(
            rs.getLong("id"), rs.getLong("report_line_id"), rs.getLong("recording_id"),
            rs.getBigDecimal("score"), rs.getBigDecimal("title_similarity"),
            rs.getBoolean("contributor_match"), (Integer) rs.getObject("duration_diff_seconds"),
            rs.getString("basis"), rs.getString("status"),
            rs.getObject("decided_at", java.time.OffsetDateTime.class));

    // ---------- 批次 ----------

    public long insertBatch(ReportBatch b) {
        return jdbc.queryForObject("insert into report_batch(batch_ref, platform_id, period_start, period_end, "
                + "source_file, declared_total_count, declared_gross_minor, declared_fee_minor, "
                + "declared_net_minor, currency, status, reconciliation) "
                + "values (?,?,?,?,?,?,?,?,?,?, 'RECEIVED', 'PENDING') returning id",
                Long.class, b.batchRef(), b.platformId(), b.periodStart(), b.periodEnd(), b.sourceFile(),
                b.declaredTotalCount(), b.declaredGrossMinor(), b.declaredFeeMinor(),
                b.declaredNetMinor(), b.currency());
    }

    public Optional<ReportBatch> findBatchByRef(String ref) {
        return jdbc.query("select * from report_batch where batch_ref = ?", BATCH_MAPPER, ref)
                .stream().findFirst();
    }

    public Optional<ReportBatch> findBatchById(long id) {
        return jdbc.query("select * from report_batch where id = ?", BATCH_MAPPER, id)
                .stream().findFirst();
    }

    public List<ReportBatch> allBatches() {
        return jdbc.query("select * from report_batch order by id", BATCH_MAPPER);
    }

    public void updateParseCounters(long batchId, int valid, int bad, int dup, String reconciliation,
                                    String status, String note) {
        jdbc.update("update report_batch set parse_valid_count=?, parse_bad_count=?, parse_dup_count=?, "
                + "reconciliation=?, status=?, note=? where id=?", valid, bad, dup, reconciliation, status, note, batchId);
    }

    // ---------- 明细行 ----------

    public long insertLine(ReportLine l) {
        return jdbc.queryForObject("insert into report_line(batch_id, platform_line_no, revision_no, "
                + "supersedes_line_id, platform_item_code, reported_title, reported_artist, reported_duration, "
                + "usage_date, territory, media, usage_count, gross_minor, fee_minor, net_minor, currency, "
                + "raw_text, parse_status, reject_reason, usage_status) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id",
                Long.class, l.batchId(), l.platformLineNo(), l.revisionNo(), l.supersedesLineId(),
                l.platformItemCode(), l.reportedTitle(), l.reportedArtist(), l.reportedDuration(),
                l.usageDate(), l.territory(), l.media(), l.usageCount(), l.grossMinor(), l.feeMinor(),
                l.netMinor(), l.currency(), l.rawText(), l.parseStatus(), l.rejectReason(),
                l.usageStatus() == null ? "NEW" : l.usageStatus());
    }

    /**
     * 平台级原行编号的最新修订（跨批次）：
     * 晚到更正/撤销可能在后续批次到达，需关联到平台同一原行编号的原件。
     */
    public Optional<ReportLine> findLatestRevision(long platformId, String lineNo) {
        return jdbc.query("select rl.* from report_line rl join report_batch rb on rb.id = rl.batch_id "
                + "where rb.platform_id=? and rl.platform_line_no=? "
                + "order by rl.revision_no desc, rl.id desc limit 1", LINE_MAPPER, platformId, lineNo)
                .stream().findFirst();
    }

    public Optional<ReportLine> findLineById(long id) {
        return jdbc.query("select * from report_line where id = ?", LINE_MAPPER, id)
                .stream().findFirst();
    }

    public List<ReportLine> linesOfBatch(long batchId) {
        return jdbc.query("select * from report_line where batch_id=? order by id", LINE_MAPPER, batchId);
    }

    /** 某平台内部编号下所有现行有效行（候选预览用） */
    public List<ReportLine> activeLinesByItem(String itemCode) {
        return jdbc.query("select * from report_line where platform_item_code=? and parse_status='VALID' "
                + "and usage_status='NEW' order by id", LINE_MAPPER, itemCode);
    }

    /**
     * 结算范围内的有效现行行（VALID 且未被撤销/取代）；
     * reportBatchIds 非空时仅取指定批次；只有核对通过(ACCEPTED)的批次才能进入结算，
     * RECONCILING 批次的行被排除，避免漏行/坏行未处理就出账。
     */
    public List<ReportLine> activeLinesForSettlement(Long platformId, String periodStart, String periodEnd,
                                                     java.util.List<Long> reportBatchIds,
                                                     long offset, int limit) {
        boolean byBatches = reportBatchIds != null && !reportBatchIds.isEmpty();
        String sql = "select rl.* from report_line rl join report_batch rb on rb.id = rl.batch_id "
                + "where rl.parse_status='VALID' and rl.usage_status='NEW' and rb.status='ACCEPTED' "
                + "and (?::bigint is null or rb.platform_id = ?) "
                + "and rl.usage_date between ?::date and ?::date "
                + (byBatches ? " and rl.batch_id in (" + placeholders(reportBatchIds.size()) + ")" : "")
                + " order by rl.id limit ? offset ?";
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(platformId); args.add(platformId); args.add(periodStart); args.add(periodEnd);
        if (byBatches) {
            args.addAll(reportBatchIds);
        }
        args.add(limit); args.add(offset);
        return jdbc.query(sql, LINE_MAPPER, args.toArray());
    }

    public long countActiveLinesForSettlement(Long platformId, String periodStart, String periodEnd,
                                              java.util.List<Long> reportBatchIds) {
        boolean byBatches = reportBatchIds != null && !reportBatchIds.isEmpty();
        String sql = "select count(*) from report_line rl join report_batch rb on rb.id=rl.batch_id "
                + "where rl.parse_status='VALID' and rl.usage_status='NEW' and rb.status='ACCEPTED' "
                + "and (?::bigint is null or rb.platform_id = ?) "
                + "and rl.usage_date between ?::date and ?::date "
                + (byBatches ? " and rl.batch_id in (" + placeholders(reportBatchIds.size()) + ")" : "");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(platformId); args.add(platformId); args.add(periodStart); args.add(periodEnd);
        if (byBatches) {
            args.addAll(reportBatchIds);
        }
        Long c = jdbc.queryForObject(sql, Long.class, args.toArray());
        return c == null ? 0 : c;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    public void markLineSuperseded(long lineId, long byLineId) {
        jdbc.update("update report_line set usage_status='SUPERSEDED', superseded_by_line_id=? where id=?",
                byLineId, lineId);
    }

    /** 使用量撤销：关联原报告行，标记 REVOKED；已采用原件不覆盖，晚到撤销另成状态 */
    public void markLineRevoked(long lineId) {
        jdbc.update("update report_line set usage_status='REVOKED' where id=?", lineId);
    }

    // ---------- 候选 ----------

    public void insertCandidate(long reportLineId, long recordingId, java.math.BigDecimal score,
                                java.math.BigDecimal titleSim, boolean contributorMatch,
                                Integer durationDiff, String basis) {
        jdbc.update("insert into mapping_candidate(report_line_id, recording_id, score, title_similarity, "
                + "contributor_match, duration_diff_seconds, basis) values (?,?,?,?,?,?,?)",
                reportLineId, recordingId, score, titleSim, contributorMatch, durationDiff, basis);
    }

    public List<MappingCandidate> candidatesOfLine(long reportLineId) {
        return jdbc.query("select * from mapping_candidate where report_line_id=? order by score desc, id",
                CANDIDATE_MAPPER, reportLineId);
    }

    public Optional<MappingCandidate> findCandidateById(long id) {
        return jdbc.query("select * from mapping_candidate where id=?", CANDIDATE_MAPPER, id)
                .stream().findFirst();
    }

    public void decideCandidate(long id, String status) {
        jdbc.update("update mapping_candidate set status=?, decided_at=now() where id=?", status, id);
    }

    // ---------- 挂账余额 ----------

    public long insertHold(long settlementBatchId, Long reportLineId, String rightType, String type,
                           long amountMinor, String currency, String reason) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(new org.springframework.jdbc.core.PreparedStatementCreatorFactory(
                "insert into hold_balance(settlement_batch_id, report_line_id, right_type, balance_type, "
                        + "amount_minor, currency, reason) values (?,?,?,?,?,?,?)",
                java.sql.Types.BIGINT, java.sql.Types.BIGINT, java.sql.Types.VARCHAR,
                java.sql.Types.VARCHAR, java.sql.Types.BIGINT, java.sql.Types.CHAR,
                java.sql.Types.VARCHAR)
                .newPreparedStatementCreator(new Object[]{settlementBatchId, reportLineId, rightType,
                        type, amountMinor, currency, reason}), keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1 : key.longValue();
    }

    public List<HoldBalance> holdsOfBatch(long batchId) {
        return jdbc.query("select * from hold_balance where settlement_batch_id=? order by id",
                (rs, n) -> new HoldBalance(rs.getLong("id"), rs.getLong("settlement_batch_id"),
                        (Long) rs.getObject("report_line_id"), rs.getString("right_type"),
                        rs.getString("balance_type"), rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getString("reason"), rs.getBoolean("resolved"), rs.getString("resolution_note"),
                        rs.getLong("frozen_minor")),
                batchId);
    }

    public Optional<HoldBalance> findHoldById(long id) {
        return jdbc.query("select * from hold_balance where id=?", (rs, n) -> new HoldBalance(
                rs.getLong("id"), rs.getLong("settlement_batch_id"),
                (Long) rs.getObject("report_line_id"), rs.getString("right_type"),
                rs.getString("balance_type"), rs.getLong("amount_minor"), rs.getString("currency"),
                rs.getString("reason"), rs.getBoolean("resolved"), rs.getString("resolution_note"),
                rs.getLong("frozen_minor")), id).stream().findFirst();
    }

    /** 挂账增加冻结额，不得超过挂账余额（重叠争议求并集后只会调用一次） */
    public void addHoldFrozen(long holdId, long delta) {
        int n = jdbc.update("update hold_balance set frozen_minor = frozen_minor + ? "
                + "where id = ? and frozen_minor + ? <= amount_minor", delta, holdId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException(
                    "挂账 " + holdId + " 可冻结余额不足，不能重复冻结同一份额");
        }
    }

    /** 结算引擎回填：挂账台账行与分摊行一一对应，托管据此在两侧同步冻结留痕 */
    public void linkHoldAllocation(long holdId, long allocationId) {
        jdbc.update("update hold_balance set settlement_allocation_id=? where id=?", allocationId, holdId);
    }

    public Optional<Long> holdIdByAllocation(long allocationId) {
        return jdbc.query("select id from hold_balance where settlement_allocation_id=?",
                (rs, n) -> rs.getLong(1), allocationId).stream().findFirst();
    }

    /** 无主挂账转付受益人：挂账余额与冻结额同步核减 */
    public void reduceHold(long holdId, long delta) {
        int n = jdbc.update("update hold_balance set amount_minor = amount_minor - ?, "
                + "frozen_minor = frozen_minor - ? where id=? and frozen_minor >= ?",
                delta, delta, holdId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("挂账 " + holdId + " 余额不足以转付");
        }
    }

    /** 无主挂账解除冻结：仅回退冻结额，钱仍留在挂账台账 */
    public void releaseHoldFrozen(long holdId, long delta) {
        int n = jdbc.update("update hold_balance set frozen_minor = frozen_minor - ? "
                + "where id=? and frozen_minor >= ?", delta, holdId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("挂账 " + holdId + " 冻结余额不足以释放");
        }
    }
}
