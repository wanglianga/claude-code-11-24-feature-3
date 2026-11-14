package cc.royalty.repo;

import cc.royalty.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<SettlementBatch> BATCH_MAPPER = (rs, n) -> new SettlementBatch(
            rs.getLong("id"), rs.getString("batch_no"), rs.getString("batch_type"),
            (Long) rs.getObject("platform_id"), rs.getDate("period_start").toLocalDate(),
            rs.getDate("period_end").toLocalDate(), rs.getString("status"),
            rs.getString("contract_snapshot_fingerprint"), rs.getString("report_scope"),
            rs.getString("locked_by"), rs.getString("approved_by"),
            rs.getObject("approved_at", java.time.OffsetDateTime.class), rs.getString("note"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("calculated_at", java.time.OffsetDateTime.class));

    private static final RowMapper<SettlementLine> LINE_MAPPER = (rs, n) -> new SettlementLine(
            rs.getLong("id"), rs.getLong("settlement_batch_id"), rs.getLong("report_line_id"),
            rs.getString("right_type"), (Long) rs.getObject("recording_id"),
            (Long) rs.getObject("work_id"), rs.getDate("usage_date").toLocalDate(),
            rs.getString("territory"), rs.getString("media"), rs.getString("allocation_scope"),
            rs.getLong("distributable_minor"), rs.getLong("fee_minor"), rs.getString("basis"),
            rs.getBigDecimal("rate_used"), rs.getString("chosen_contract_ids"),
            rs.getBoolean("conflict_flag"), rs.getString("conflict_detail"), rs.getString("status"),
            rs.getString("hold_reason"), rs.getString("source_fingerprint"), rs.getInt("segment_no"));

    private static final RowMapper<SettlementAllocation> ALLOC_MAPPER = (rs, n) -> new SettlementAllocation(
            rs.getLong("id"), rs.getLong("settlement_line_id"), (Long) rs.getObject("payee_id"),
            (Long) rs.getObject("contract_id"), rs.getString("alloc_status"),
            rs.getLong("share_numerator"), rs.getLong("share_denominator"), rs.getLong("amount_minor"),
            rs.getLong("frozen_minor"), rs.getLong("clawed_minor"));

    private static final RowMapper<Payable> PAYABLE_MAPPER = (rs, n) -> new Payable(
            rs.getLong("id"), rs.getLong("settlement_batch_id"), rs.getLong("payee_id"),
            rs.getString("right_type"), rs.getString("currency"), rs.getLong("amount_minor"),
            rs.getLong("paid_minor"), rs.getString("status"), rs.getLong("frozen_minor"),
            rs.getLong("frozen_release_minor"), rs.getString("source_kind"),
            (Long) rs.getObject("arbitration_id"));

    // ---------- 范围锁（同一结算范围不可重复出账） ----------

    /** @return true 表示成功获得锁 */
    public boolean tryAcquireScopeLock(String scopeKey, long settlementBatchId, String actor) {
        try {
            jdbc.update("insert into settlement_lock(scope_key, settlement_batch_id, locked_by) values (?,?,?)",
                    scopeKey, settlementBatchId, actor);
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public void releaseScopeLock(String scopeKey) {
        jdbc.update("delete from settlement_lock where scope_key = ?", scopeKey);
    }

    public Optional<Long> batchIdForScope(String scopeKey) {
        return jdbc.query("select settlement_batch_id from settlement_lock where scope_key=?",
                (rs, n) -> rs.getLong(1), scopeKey).stream().findFirst();
    }

    public void updateScopeLockBatch(String scopeKey, long batchId) {
        jdbc.update("update settlement_lock set settlement_batch_id=? where scope_key=?", batchId, scopeKey);
    }

    // ---------- 结算批次 ----------

    public long insertBatch(String batchNo, String batchType, Long platformId,
                            java.time.LocalDate from, java.time.LocalDate to,
                            String scopeKey, String actor, String reportScope) {
        return jdbc.queryForObject("insert into settlement_batch(batch_no, batch_type, platform_id, "
                + "period_start, period_end, status, report_scope, locked_by) "
                + "values (?,?,?,?,?, 'CREATED', ?, ?) returning id",
                Long.class, batchNo, batchType, platformId, from, to, reportScope, actor);
    }

    public void markCalculated(long batchId, String fingerprint) {
        jdbc.update("update settlement_batch set status='CALCULATED', calculated_at=now(), "
                + "contract_snapshot_fingerprint=? where id=?", fingerprint, batchId);
    }

    public void updateBatchNo(long batchId, String batchNo) {
        jdbc.update("update settlement_batch set batch_no=? where id=?", batchNo, batchId);
    }

    public void updateStatus(long batchId, String status) {
        jdbc.update("update settlement_batch set status=? where id=?", status, batchId);
    }

    public void approve(long batchId, String actor) {
        jdbc.update("update settlement_batch set status='APPROVED', approved_by=?, approved_at=now() where id=?",
                actor, batchId);
    }

    public Optional<SettlementBatch> findBatchByNo(String no) {
        return jdbc.query("select * from settlement_batch where batch_no=?", BATCH_MAPPER, no)
                .stream().findFirst();
    }

    public Optional<SettlementBatch> findBatchById(long id) {
        return jdbc.query("select * from settlement_batch where id=?", BATCH_MAPPER, id)
                .stream().findFirst();
    }

    public List<SettlementBatch> allBatches() {
        return jdbc.query("select * from settlement_batch order by id", BATCH_MAPPER);
    }

    // ---------- 逐笔计算与分摊 ----------

    public long insertLine(SettlementLine l) {
        return jdbc.queryForObject("insert into settlement_line(settlement_batch_id, report_line_id, right_type, "
                + "recording_id, work_id, usage_date, territory, media, allocation_scope, distributable_minor, "
                + "fee_minor, basis, rate_used, chosen_contract_ids, conflict_flag, conflict_detail, status, "
                + "hold_reason, source_fingerprint, segment_no) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id",
                Long.class, l.settlementBatchId(), l.reportLineId(), l.rightType(), l.recordingId(),
                l.workId(), l.usageDate(), l.territory(), l.media(), l.allocationScope(),
                l.distributableMinor(), l.feeMinor(), l.basis(), l.rateUsed(), l.chosenContractIds(),
                l.conflictFlag(), l.conflictDetail(), l.status(), l.holdReason(),
                l.sourceFingerprint(), l.segmentNo());
    }

    public long insertAllocation(long settlementLineId, Long payeeId, Long contractId, String status,
                                 long num, long den, long amount) {
        return jdbc.queryForObject("insert into settlement_allocation(settlement_line_id, payee_id, contract_id, alloc_status, "
                + "share_numerator, share_denominator, amount_minor) values (?,?,?,?,?,?,?) returning id",
                Long.class, settlementLineId, payeeId, contractId, status, num, den, amount);
    }

    public List<SettlementLine> linesOfBatch(long batchId) {
        return jdbc.query("select * from settlement_line where settlement_batch_id=? order by id",
                LINE_MAPPER, batchId);
    }

    public void updateChosenContracts(long lineId, String contractIds) {
        jdbc.update("update settlement_line set chosen_contract_ids=? where id=?", contractIds, lineId);
    }

    public Optional<SettlementLine> findLineById(long id) {
        return jdbc.query("select * from settlement_line where id=?", LINE_MAPPER, id)
                .stream().findFirst();
    }

    public List<SettlementAllocation> allocationsOfLine(long lineId) {
        return jdbc.query("select * from settlement_allocation where settlement_line_id=? order by id",
                ALLOC_MAPPER, lineId);
    }

    /** 已处理过的报告行（支持中断后按分段续算，跳过已处理分段） */
    public java.util.Set<Long> processedReportLines(long batchId) {
        return new java.util.HashSet<>(jdbc.query("select distinct report_line_id from settlement_line "
                + "where settlement_batch_id=?", (rs, n) -> rs.getLong(1), batchId));
    }

    /** 已在其他结算批次出现过的报告行：保证同一平台原行全系统最多出账一次 */
    public java.util.Set<Long> globallySettledLineIds(long excludeBatchId) {
        return new java.util.HashSet<>(jdbc.query(
                "select distinct report_line_id from settlement_line where settlement_batch_id <> ?",
                (rs, n) -> rs.getLong(1), excludeBatchId));
    }

    // ---------- 应付汇总 ----------

    public long upsertPayable(long batchId, long payeeId, String rightType, String currency, long amount) {
        return jdbc.queryForObject("""
                insert into payable(settlement_batch_id, payee_id, right_type, currency, amount_minor,
                    status, source_kind)
                values (?,?,?,?,?, 'CALCULATED', 'SETTLEMENT')
                on conflict (settlement_batch_id, payee_id, right_type, currency)
                where source_kind = 'SETTLEMENT'
                do update set amount_minor = payable.amount_minor + excluded.amount_minor
                returning id""", Long.class, batchId, payeeId, rightType, currency, amount);
    }

    public List<Payable> payablesOfBatch(long batchId) {
        return jdbc.query("select * from payable where settlement_batch_id=? order by id", PAYABLE_MAPPER, batchId);
    }

    public Optional<Payable> findPayableById(long id) {
        return jdbc.query("select * from payable where id=?", PAYABLE_MAPPER, id).stream().findFirst();
    }

    public void updatePayableStatus(long id, String status) {
        jdbc.update("update payable set status=? where id=?", status, id);
    }

    public void addPaidAmount(long payableId, long amount) {
        jdbc.update("update payable set paid_minor = paid_minor + ? where id = ?", amount, payableId);
    }

    // ---------- 托管冻结在来源侧的留痕 ----------

    /** 分摊行增加冻结额；冻结额不得超过未冻结余额 */
    public void addAllocationFrozen(long allocationId, long delta) {
        int n = jdbc.update("update settlement_allocation set frozen_minor = frozen_minor + ? "
                + "where id = ? and frozen_minor + ? <= amount_minor", delta, allocationId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException(
                    "分摊 " + allocationId + " 可冻结余额不足，不能重复冻结同一份额");
        }
    }

    public long allocationFrozen(long allocationId) {
        Long v = jdbc.queryForObject("select frozen_minor from settlement_allocation where id=?",
                Long.class, allocationId);
        return v == null ? 0 : v;
    }

    /** 分摊行增加已追回额；冻结+追回不得超过该行金额（重复争议对同一份额只追回一次） */
    public void addAllocationClawed(long allocationId, long delta) {
        int n = jdbc.update("update settlement_allocation set clawed_minor = clawed_minor + ? "
                + "where id=? and frozen_minor + clawed_minor + ? <= amount_minor",
                delta, allocationId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException(
                    "分摊 " + allocationId + " 可追回额度不足，不能对同一份额重复追回");
        }
    }

    /** 应付增加冻结额（冻结部分不再出现在普通待付） */
    public void addPayableFrozen(long payableId, long delta) {
        int n = jdbc.update("update payable set frozen_minor = frozen_minor + ? "
                + "where id = ? and frozen_minor + ? <= amount_minor - paid_minor", delta, payableId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("应付 " + payableId + " 可冻结余额不足");
        }
    }

    /** 托管释放：冻结额减少、已释放冻结额增加（资金转为托管应付，不改变应付总额） */
    public void releasePayableFrozen(long payableId, long delta) {
        int n = jdbc.update("update payable set frozen_minor = frozen_minor - ?, "
                + "frozen_release_minor = frozen_release_minor + ? where id = ? and frozen_minor >= ?",
                delta, delta, payableId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("应付 " + payableId + " 冻结余额不足以释放");
        }
    }

    /** 托管转付：原权利人应付的冻结额直接核销（金额离开原收款人，转由受益人应付承接） */
    public void consumePayableFrozen(long payableId, long delta) {
        int n = jdbc.update("update payable set frozen_minor = frozen_minor - ?, "
                + "amount_minor = amount_minor - ? where id = ? and frozen_minor >= ?",
                delta, delta, payableId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("应付 " + payableId + " 冻结余额不足以转付");
        }
    }

    /**
     * 托管释放/转付形成的应付：按 批次/权利人/权利类型/币种/来源种类/裁决 隔离，
     * 不同裁决各自成行；同一裁决重复执行由调用方判重，不产生重复应付与重复付款。
     */
    public long upsertEscrowPayable(long batchId, long payeeId, String rightType,
                                    String currency, long amount, String sourceKind,
                                    Long arbitrationId) {
        return jdbc.queryForObject("""
                insert into payable(settlement_batch_id, payee_id, right_type, currency, amount_minor,
                    paid_minor, status, frozen_minor, source_kind, arbitration_id)
                values (?,?,?,?,?, 0, 'APPROVED', 0, ?, ?)
                on conflict (settlement_batch_id, payee_id, right_type, currency, source_kind, arbitration_id)
                where source_kind <> 'SETTLEMENT'
                do update set amount_minor = payable.amount_minor + excluded.amount_minor
                returning id""", Long.class, batchId, payeeId, rightType, currency, amount,
                sourceKind, arbitrationId);
    }

    /** 托管来源的应付（释放/转付），普通付款运行不得拾取 */
    public List<Payable> escrowSourcePayables() {
        return jdbc.query("select * from payable where source_kind <> 'SETTLEMENT' order by id",
                PAYABLE_MAPPER);
    }

    /** 某托管账户经裁决执行线产生的托管应付（守恒视图的待付口径） */
    public List<Payable> escrowPayablesOfAccount(long accountId) {
        return jdbc.query("""
                select distinct p.* from payable p
                join arbitration_execution_line l on l.payable_id = p.id
                join escrow_freeze f on f.id = l.escrow_freeze_id
                where f.account_id = ? order by p.id""", PAYABLE_MAPPER, accountId);
    }

    /** 分摊行回退冻结额（托管释放/转付时） */
    public void releaseAllocationFrozen(long allocationId, long delta) {
        int n = jdbc.update("update settlement_allocation set frozen_minor = frozen_minor - ? "
                + "where id=? and frozen_minor >= ?", delta, allocationId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("分摊 " + allocationId + " 冻结余额不足");
        }
    }

    /** 按批次/权利人/权利类型/币种定位应付（冻结来源行反查用） */
    public Optional<Payable> findPayable(long batchId, long payeeId, String rightType, String currency) {
        return jdbc.query("select * from payable where settlement_batch_id=? and payee_id=? "
                + "and right_type=? and currency=? order by id limit 1",
                PAYABLE_MAPPER, batchId, payeeId, rightType, currency).stream().findFirst();
    }

    /** 争议求交结果行：结算分摊 + 所属结算行维度 + 可对应挂账 id */
    public record DisputeAllocRow(SettlementAllocation allocation, SettlementLine line, Long holdBalanceId) {
    }

    /**
     * 争议声明与结算项求交：按录音、权利类别、使用时段、地区过滤分摊行。
     * 只取数据行（settlement_line.right_type 为具体权利类型，排除 UNBUDGETED 汇总行的混淆）。
     */
    public List<DisputeAllocRow> allocationsForDisputeScope(long recordingId, String rightType,
                                                            java.time.LocalDate from, java.time.LocalDate to,
                                                            List<String> territories) {
        String terrs = String.join(",", territories);
        String sql = """
                select a.*, hb.id as hold_id from settlement_allocation a
                join settlement_line sl on sl.id = a.settlement_line_id
                left join hold_balance hb on hb.settlement_allocation_id = a.id
                where sl.recording_id = ? and sl.right_type = ?
                  and sl.usage_date between ? and ?
                  and ('GLOBAL' = any(string_to_array(?, ',')) or sl.territory = any(string_to_array(?, ',')))
                order by a.id""";
        return jdbc.query(sql, (rs, n) -> {
            SettlementAllocation a = new SettlementAllocation(
                    rs.getLong("id"), rs.getLong("settlement_line_id"), (Long) rs.getObject("payee_id"),
                    (Long) rs.getObject("contract_id"), rs.getString("alloc_status"),
                    rs.getLong("share_numerator"), rs.getLong("share_denominator"),
                    rs.getLong("amount_minor"), rs.getLong("frozen_minor"), rs.getLong("clawed_minor"));
            SettlementLine line = findLineById(a.settlementLineId()).orElseThrow();
            return new DisputeAllocRow(a, line, (Long) rs.getObject("hold_id"));
        }, recordingId, rightType, from, to, terrs, terrs);
    }

    /** 裁决撤回：把已转为托管释放待付的冻结额退回原应付冻结（frozen_release → frozen） */
    public void revertReleasedPayableFrozen(long payableId, long delta) {
        int n = jdbc.update("update payable set frozen_minor = frozen_minor + ?, "
                + "frozen_release_minor = frozen_release_minor - ? where id=? and frozen_release_minor >= ?",
                delta, delta, payableId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("应付 " + payableId + " 已释放冻结额不足以回退");
        }
    }

    /** 暂停未付托管款后核减应付；核减到 0 标记 FAILED */
    public void reduceEscrowPayable(long payableId, long amount) {
        jdbc.update("update payable set amount_minor = amount_minor - ? where id=? and amount_minor >= ?",
                amount, payableId, amount);
        jdbc.update("update payable set status='FAILED' where id=? and amount_minor = paid_minor", payableId);
    }
}