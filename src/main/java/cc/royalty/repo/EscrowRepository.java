package cc.royalty.repo;

import cc.royalty.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 争议托管台账仓储：争议案件、托管账户、冻结单元及其争议覆盖、不可变分录、
 * 裁决与执行记录、追回事项。SQL 显式可审，无 ORM 隐式行为。
 */
@Repository
public class EscrowRepository {

    private final JdbcTemplate jdbc;

    public EscrowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- RowMappers ----------

    private static final RowMapper<DisputeCase> DISPUTE_MAPPER = (rs, n) -> new DisputeCase(
            rs.getLong("id"), rs.getString("dispute_no"), rs.getLong("recording_id"),
            rs.getString("right_type"), DisputeCase.parseTerritories(rs.getString("territories")),
            rs.getDate("period_start").toLocalDate(), rs.getDate("period_end").toLocalDate(),
            rs.getLong("ratio_numerator"), rs.getLong("ratio_denominator"),
            (Long) rs.getObject("claimant_payee_id"), (Long) rs.getObject("respondent_payee_id"),
            rs.getString("legal_ref"), rs.getString("reason"), rs.getString("status"),
            rs.getObject("withdrawn_at", java.time.OffsetDateTime.class),
            rs.getString("withdraw_reason"), rs.getString("created_by"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<EscrowAccount> ACCOUNT_MAPPER = (rs, n) -> new EscrowAccount(
            rs.getLong("id"), rs.getString("account_no"), rs.getString("currency"),
            rs.getString("title"), rs.getString("status"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<EscrowFreeze> FREEZE_MAPPER = (rs, n) -> new EscrowFreeze(
            rs.getLong("id"), rs.getLong("account_id"), rs.getLong("settlement_allocation_id"),
            (Long) rs.getObject("hold_balance_id"), rs.getLong("settlement_batch_id"),
            rs.getLong("report_line_id"), rs.getLong("recording_id"), rs.getString("right_type"),
            (Long) rs.getObject("payee_id"), rs.getString("territory"),
            rs.getDate("usage_date").toLocalDate(), rs.getString("currency"),
            rs.getLong("original_amount_minor"), rs.getLong("frozen_amount_minor"),
            EscrowFreeze.parseDisputeIds(rs.getString("dispute_ids")), rs.getString("status"),
            rs.getLong("released_minor"), rs.getLong("transferred_minor"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<EscrowEntry> ENTRY_MAPPER = (rs, n) -> new EscrowEntry(
            rs.getLong("id"), rs.getLong("account_id"), rs.getLong("entry_seq"),
            rs.getString("entry_type"), rs.getLong("amount_minor"), rs.getString("currency"),
            (Long) rs.getObject("escrow_freeze_id"), (Long) rs.getObject("dispute_id"),
            (Long) rs.getObject("arbitration_id"), (Long) rs.getObject("payment_request_id"),
            (Long) rs.getObject("counterparty_payee_id"), rs.getString("legal_ref"),
            rs.getString("note"), rs.getLong("balance_after_minor"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<Arbitration> ARBITRATION_MAPPER = (rs, n) -> new Arbitration(
            rs.getLong("id"), rs.getString("arbitration_no"), rs.getLong("dispute_id"),
            rs.getLong("recording_id"), rs.getString("right_type"),
            Arbitration.parseTerritories(rs.getString("territories")),
            rs.getDate("period_start").toLocalDate(), rs.getDate("period_end").toLocalDate(),
            rs.getString("outcome"), (Long) rs.getObject("beneficiary_payee_id"),
            rs.getString("legal_basis"), rs.getString("note"), rs.getString("status"),
            rs.getObject("revoked_at", java.time.OffsetDateTime.class),
            rs.getString("revoke_reason"), rs.getString("created_by"),
            rs.getObject("created_at", java.time.OffsetDateTime.class));

    private static final RowMapper<ClawbackItem> CLAWBACK_MAPPER = (rs, n) -> new ClawbackItem(
            rs.getLong("id"), rs.getString("clawback_no"), rs.getLong("dispute_id"),
            rs.getLong("settlement_batch_id"), rs.getLong("payable_id"), rs.getLong("payee_id"),
            rs.getString("currency"), rs.getLong("amount_minor"), rs.getString("status"),
            rs.getString("reason"), rs.getObject("recovered_at", java.time.OffsetDateTime.class),
            rs.getString("recovery_ref"), rs.getObject("created_at", java.time.OffsetDateTime.class));

    // ---------- 争议案件 ----------

    public long insertDispute(String no, long recordingId, String rightType, String territories,
                              LocalDate from, LocalDate to, long ratioNum, long ratioDen,
                              Long claimantId, Long respondentId, String legalRef,
                              String reason, String actor) {
        return jdbc.queryForObject("""
                insert into dispute_case(dispute_no, recording_id, right_type, territories,
                    period_start, period_end, ratio_numerator, ratio_denominator,
                    claimant_payee_id, respondent_payee_id, legal_ref, reason, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?) returning id""",
                Long.class, no, recordingId, rightType, territories, from, to, ratioNum, ratioDen,
                claimantId, respondentId, legalRef, reason, actor);
    }

    public Optional<DisputeCase> findDisputeByNo(String no) {
        return jdbc.query("select * from dispute_case where dispute_no=?", DISPUTE_MAPPER, no)
                .stream().findFirst();
    }

    public Optional<DisputeCase> findDisputeById(long id) {
        return jdbc.query("select * from dispute_case where id=?", DISPUTE_MAPPER, id)
                .stream().findFirst();
    }

    public List<DisputeCase> allDisputes() {
        return jdbc.query("select * from dispute_case order by id", DISPUTE_MAPPER);
    }

    public List<DisputeCase> activeDisputes() {
        return jdbc.query("select * from dispute_case where status='ACTIVE' order by id", DISPUTE_MAPPER);
    }

    public void withdrawDispute(long id, String reason) {
        jdbc.update("update dispute_case set status='WITHDRAWN', withdrawn_at=now(), withdraw_reason=? "
                + "where id=?", reason, id);
    }

    public void renameDispute(long id, String no) {
        jdbc.update("update dispute_case set dispute_no=? where id=?", no, id);
    }

    public void renameArbitration(long id, String no) {
        jdbc.update("update arbitration set arbitration_no=? where id=?", no, id);
    }

    // ---------- 托管账户 ----------

    public long insertAccount(String accountNo, String currency, String title) {
        return jdbc.queryForObject(
                "insert into escrow_account(account_no, currency, title) values (?,?,?) returning id",
                Long.class, accountNo, currency, title);
    }

    public Optional<EscrowAccount> findAccountByNo(String no) {
        return jdbc.query("select * from escrow_account where account_no=?", ACCOUNT_MAPPER, no)
                .stream().findFirst();
    }

    public Optional<EscrowAccount> findAccountById(long id) {
        return jdbc.query("select * from escrow_account where id=?", ACCOUNT_MAPPER, id)
                .stream().findFirst();
    }

    public List<EscrowAccount> allAccounts() {
        return jdbc.query("select * from escrow_account order by id", ACCOUNT_MAPPER);
    }

    public void linkDispute(long accountId, long disputeId) {
        jdbc.update("insert into escrow_dispute_link(account_id, dispute_id) values (?,?) "
                + "on conflict do nothing", accountId, disputeId);
    }

    public List<Long> disputeIdsOfAccount(long accountId) {
        return jdbc.query("select dispute_id from escrow_dispute_link where account_id=? order by dispute_id",
                (rs, n) -> rs.getLong(1), accountId);
    }

    // ---------- 冻结单元 ----------

    public long insertFreeze(EscrowFreeze f) {
        return jdbc.queryForObject("""
                insert into escrow_freeze(account_id, settlement_allocation_id, hold_balance_id,
                    settlement_batch_id, report_line_id, recording_id, right_type, payee_id,
                    territory, usage_date, currency, original_amount_minor, frozen_amount_minor,
                    dispute_ids, status)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'FROZEN') returning id""",
                Long.class, f.accountId(), f.settlementAllocationId(), f.holdBalanceId(),
                f.settlementBatchId(), f.reportLineId(), f.recordingId(), f.rightType(),
                f.payeeId(), f.territory(), f.usageDate(), f.currency(),
                f.originalAmountMinor(), f.frozenAmountMinor(),
                EscrowFreeze.joinDisputeIds(f.disputeIds()));
    }

    public Optional<EscrowFreeze> findFreezeByAllocation(long allocationId) {
        return jdbc.query("select * from escrow_freeze where settlement_allocation_id=?",
                FREEZE_MAPPER, allocationId).stream().findFirst();
    }

    public Optional<EscrowFreeze> findFreezeById(long id) {
        return jdbc.query("select * from escrow_freeze where id=?", FREEZE_MAPPER, id)
                .stream().findFirst();
    }

    public List<EscrowFreeze> freezesOfAccount(long accountId) {
        return jdbc.query("select * from escrow_freeze where account_id=? order by id",
                FREEZE_MAPPER, accountId);
    }

    public List<EscrowFreeze> openFreezesOfDispute(long disputeId) {
        return jdbc.query("select f.* from escrow_freeze f join escrow_freeze_cover c "
                + "on c.escrow_freeze_id = f.id where c.dispute_id=? "
                + "and (f.frozen_amount_minor - f.released_minor - f.transferred_minor) > 0 order by f.id",
                FREEZE_MAPPER, disputeId);
    }

    public void appendFreeze(long freezeId, long delta, List<Long> disputeIds) {
        jdbc.update("update escrow_freeze set frozen_amount_minor = frozen_amount_minor + ?, "
                + "dispute_ids = ?, status = case when released_minor + transferred_minor > 0 "
                + "then 'PARTIALLY_RELEASED' else 'FROZEN' end where id=?",
                delta, EscrowFreeze.joinDisputeIds(disputeIds), freezeId);
    }

    public void markFreezeProgress(long freezeId) {
        jdbc.update("""
                update escrow_freeze set status = case
                    when released_minor + transferred_minor = 0 then 'FROZEN'
                    when released_minor + transferred_minor >= frozen_amount_minor
                        then case when transferred_minor > 0 and released_minor = 0
                                  then 'TRANSFERRED' else 'RELEASED' end
                    else 'PARTIALLY_RELEASED' end
                where id=?""", freezeId);
    }

    public void addReleased(long freezeId, long delta) {
        jdbc.update("update escrow_freeze set released_minor = released_minor + ? where id=? "
                + "and released_minor + transferred_minor + ? <= frozen_amount_minor",
                delta, freezeId, delta);
    }

    public void addTransferred(long freezeId, long delta) {
        jdbc.update("update escrow_freeze set transferred_minor = transferred_minor + ? where id=? "
                + "and released_minor + transferred_minor + ? <= frozen_amount_minor",
                delta, freezeId, delta);
    }

    // ---------- 争议覆盖（并集归属） ----------

    public void insertCover(long freezeId, long disputeId, long coveredMinor) {
        jdbc.update("insert into escrow_freeze_cover(escrow_freeze_id, dispute_id, covered_minor) "
                + "values (?,?,?) on conflict (escrow_freeze_id, dispute_id) do nothing",
                freezeId, disputeId, coveredMinor);
    }

    public record CoverRow(long freezeId, long disputeId, long coveredMinor, long consumedMinor) {
    }

    private static final RowMapper<CoverRow> COVER_MAPPER = (rs, n) -> new CoverRow(
            rs.getLong("escrow_freeze_id"), rs.getLong("dispute_id"),
            rs.getLong("covered_minor"), rs.getLong("consumed_minor"));

    public List<CoverRow> coversOfFreeze(long freezeId) {
        return jdbc.query("select * from escrow_freeze_cover where escrow_freeze_id=? order by dispute_id",
                COVER_MAPPER, freezeId);
    }

    public List<CoverRow> coversOfDispute(long disputeId) {
        return jdbc.query("select * from escrow_freeze_cover where dispute_id=? order by escrow_freeze_id",
                COVER_MAPPER, disputeId);
    }

    public void addCoverConsumed(long freezeId, long disputeId, long delta) {
        int n = jdbc.update("update escrow_freeze_cover set consumed_minor = consumed_minor + ? "
                + "where escrow_freeze_id=? and dispute_id=? and consumed_minor + ? <= covered_minor",
                delta, freezeId, disputeId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException(
                    "争议 " + disputeId + " 对冻结 " + freezeId + " 的可处置额度不足");
        }
    }

    /** 裁决撤回：回退已消耗的争议覆盖额，使该部分重新可被裁决 */
    public void subtractCoverConsumed(long freezeId, long disputeId, long delta) {
        int n = jdbc.update("update escrow_freeze_cover set consumed_minor = consumed_minor - ? "
                + "where escrow_freeze_id=? and dispute_id=? and consumed_minor >= ?",
                delta, freezeId, disputeId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("覆盖额回退失败: 冻结 " + freezeId);
        }
    }

    // ---------- 台账分录 ----------

    public long nextEntrySeq(long accountId) {
        Long v = jdbc.queryForObject(
                "select coalesce(max(entry_seq),0) + 1 from escrow_entry where account_id=?",
                Long.class, accountId);
        return v == null ? 1 : v;
    }

    public long currentBalance(long accountId) {
        Long v = jdbc.queryForObject("""
                select coalesce(sum(case when entry_type in
                    ('FREEZE_IN','RELEASE_REVOKE','CLAWBACK_ADJ','MANUAL_ADJ')
                    then amount_minor else -amount_minor end), 0)
                from escrow_entry where account_id=?""", Long.class, accountId);
        return v == null ? 0 : v;
    }

    public long sumByType(long accountId, List<String> types) {
        if (types.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(types.size(), "?"));
        Long v = jdbc.queryForObject(
                "select coalesce(sum(amount_minor),0) from escrow_entry where account_id=? and entry_type in ("
                        + placeholders + ")", Long.class,
                java.util.stream.Stream.concat(java.util.stream.Stream.of(accountId), types.stream()).toArray());
        return v == null ? 0 : v;
    }

    public long insertEntry(EscrowEntry e) {
        return jdbc.queryForObject("""
                insert into escrow_entry(account_id, entry_seq, entry_type, amount_minor, currency,
                    escrow_freeze_id, dispute_id, arbitration_id, payment_request_id,
                    counterparty_payee_id, legal_ref, note, balance_after_minor)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?) returning id""",
                Long.class, e.accountId(), e.entrySeq(), e.entryType(), e.amountMinor(), e.currency(),
                e.escrowFreezeId(), e.disputeId(), e.arbitrationId(), e.paymentRequestId(),
                e.counterpartyPayeeId(), e.legalRef(), e.note(), e.balanceAfterMinor());
    }

    public List<EscrowEntry> entriesOfAccount(long accountId) {
        return jdbc.query("select * from escrow_entry where account_id=? order by entry_seq",
                ENTRY_MAPPER, accountId);
    }

    public Optional<EscrowEntry> findEntryById(long id) {
        return jdbc.query("select * from escrow_entry where id=?", ENTRY_MAPPER, id)
                .stream().findFirst();
    }

    // ---------- 裁决 ----------

    public long insertArbitration(String no, long disputeId, long recordingId, String rightType,
                                  String territories, LocalDate from, LocalDate to, String outcome,
                                  Long beneficiaryId, String legalBasis, String note, String actor) {
        return jdbc.queryForObject("""
                insert into arbitration(arbitration_no, dispute_id, recording_id, right_type, territories,
                    period_start, period_end, outcome, beneficiary_payee_id, legal_basis, note, created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?) returning id""",
                Long.class, no, disputeId, recordingId, rightType, territories, from, to, outcome,
                beneficiaryId, legalBasis, note, actor);
    }

    public Optional<Arbitration> findArbitrationByNo(String no) {
        return jdbc.query("select * from arbitration where arbitration_no=?", ARBITRATION_MAPPER, no)
                .stream().findFirst();
    }

    public Optional<Arbitration> findArbitrationById(long id) {
        return jdbc.query("select * from arbitration where id=?", ARBITRATION_MAPPER, id)
                .stream().findFirst();
    }

    public List<Arbitration> allArbitrations() {
        return jdbc.query("select * from arbitration order by id", ARBITRATION_MAPPER);
    }

    public List<Arbitration> effectiveArbitrations() {
        return jdbc.query("select * from arbitration where status='EFFECTIVE' order by id",
                ARBITRATION_MAPPER);
    }

    public void revokeArbitration(long id, String reason) {
        jdbc.update("update arbitration set status='REVOKED', revoked_at=now(), revoke_reason=? where id=?",
                reason, id);
    }

    public long insertExecution(long arbitrationId, int attemptSeq, String status, String actor, String note) {
        return jdbc.queryForObject("""
                insert into arbitration_execution(arbitration_id, attempt_seq, status, actor, note)
                values (?,?,?,?,?) returning id""",
                Long.class, arbitrationId, attemptSeq, status, actor, note);
    }

    public void updateExecutionStatus(long executionId, String status) {
        jdbc.update("update arbitration_execution set status=? where id=?", status, executionId);
    }

    public int nextExecutionAttempt(long arbitrationId) {
        Integer v = jdbc.queryForObject(
                "select coalesce(max(attempt_seq),0) + 1 from arbitration_execution where arbitration_id=?",
                Integer.class, arbitrationId);
        return v == null ? 1 : v;
    }

    public void insertExecutionLine(long executionId, long freezeId, String action, long amount,
                                    Long entryId, Long payableId, String coveringDisputeIds, String note) {
        jdbc.update("""
                insert into arbitration_execution_line(execution_id, escrow_freeze_id, payable_id, action,
                    amount_minor, escrow_entry_id, covering_dispute_ids, note)
                values (?,?,?,?,?,?,?,?)""",
                executionId, freezeId, payableId, action, amount, entryId, coveringDisputeIds, note);
    }

    public List<java.util.Map<String, Object>> executionLinesOfArbitration(long arbitrationId) {
        return jdbc.query("""
                select l.* from arbitration_execution_line l
                join arbitration_execution e on e.id = l.execution_id
                where e.arbitration_id=? order by l.id""", EXEC_LINE_MAPPER, arbitrationId);
    }

    /** 某托管应付关联的全部执行线（回执确认时据此释放来源侧冻结） */
    public List<java.util.Map<String, Object>> executionLinesByPayable(long payableId) {
        return jdbc.query("select * from arbitration_execution_line where payable_id=? order by id",
                EXEC_LINE_MAPPER, payableId);
    }

    /**
     * 某冻结单元已被裁决指定用途但尚未随回执出款的金额（待付占用）。
     * 两份裁决同时作用一笔余额时，新裁决只能处置剩余未占用部分，杜绝重复建待付/重复付款。
     */
    public long committedPendingOfFreeze(long freezeId) {
        Long v = jdbc.queryForObject("""
                select coalesce(sum(amount_minor - settled_minor), 0)
                from arbitration_execution_line
                where escrow_freeze_id=? and action in ('RELEASE','TRANSFER')""",
                Long.class, freezeId);
        return v == null ? 0 : v;
    }

    private static final RowMapper<java.util.Map<String, Object>> EXEC_LINE_MAPPER = (rs, n) -> {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("execution_id", rs.getLong("execution_id"));
        m.put("escrow_freeze_id", rs.getLong("escrow_freeze_id"));
        m.put("payable_id", rs.getObject("payable_id"));
        m.put("action", rs.getString("action"));
        m.put("amount_minor", rs.getLong("amount_minor"));
        m.put("settled_minor", rs.getLong("settled_minor"));
        m.put("escrow_entry_id", rs.getObject("escrow_entry_id"));
        m.put("covering_dispute_ids", rs.getString("covering_dispute_ids"));
        m.put("note", rs.getString("note"));
        return m;
    };

    /** 执行线累计已出款（回执确认时 FIFO 分摊） */
    public void addLineSettled(long lineId, long delta) {
        int n = jdbc.update("update arbitration_execution_line set settled_minor = settled_minor + ? "
                + "where id=? and settled_minor + ? <= amount_minor", delta, lineId, delta);
        if (n == 0) {
            throw new cc.royalty.common.DomainException("执行线 " + lineId + " 待付额度不足");
        }
    }

    public void subtractLineSettled(long lineId, long delta) {
        jdbc.update("update arbitration_execution_line set settled_minor = settled_minor - ? "
                + "where id=? and settled_minor >= ?", delta, lineId, delta);
    }

    /** 托管应付反查裁决编号（付款请求绑定用） */
    public Optional<Long> arbitrationIdForPayable(long payableId) {
        return jdbc.query("""
                select e.arbitration_id from arbitration_execution_line l
                join arbitration_execution e on e.id = l.execution_id
                where l.payable_id=? order by l.id limit 1""",
                (rs, n) -> rs.getLong(1), payableId).stream().findFirst();
    }

    // ---------- 追回事项 ----------

    public long insertClawback(String no, long disputeId, long batchId, long payableId, long payeeId,
                               String currency, long amount, String reason) {
        return jdbc.queryForObject("""
                insert into clawback_item(clawback_no, dispute_id, settlement_batch_id, payable_id,
                    payee_id, currency, amount_minor, reason)
                values (?,?,?,?,?,?,?,?) returning id""",
                Long.class, no, disputeId, batchId, payableId, payeeId, currency, amount, reason);
    }

    public List<ClawbackItem> clawbacksOfDispute(long disputeId) {
        return jdbc.query("select * from clawback_item where dispute_id=? order by id",
                CLAWBACK_MAPPER, disputeId);
    }

    public List<ClawbackItem> allClawbacks(String status) {
        if (status == null) {
            return jdbc.query("select * from clawback_item order by id", CLAWBACK_MAPPER);
        }
        return jdbc.query("select * from clawback_item where status=? order by id",
                CLAWBACK_MAPPER, status);
    }

    public Optional<ClawbackItem> findClawbackByNo(String no) {
        return jdbc.query("select * from clawback_item where clawback_no=?", CLAWBACK_MAPPER, no)
                .stream().findFirst();
    }

    /** 某应付已开立的追回总额（任何状态），用于追回上限：不得超过已付款 */
    public long sumClawbackByPayable(long payableId) {
        Long v = jdbc.queryForObject(
                "select coalesce(sum(amount_minor),0) from clawback_item where payable_id=?",
                Long.class, payableId);
        return v == null ? 0 : v;
    }

    public void markClawbackRecovered(long id, String recoveryRef) {
        jdbc.update("update clawback_item set status='RECOVERED', recovered_at=now(), recovery_ref=? where id=?",
                recoveryRef, id);
    }

    public void markClawbackWrittenOff(long id, String note) {
        jdbc.update("update clawback_item set status='WRITTEN_OFF', recovery_ref=? where id=?", note, id);
    }
}
