package cc.royalty.repo;

import cc.royalty.domain.AdjustmentOrder;
import cc.royalty.domain.PaymentReceipt;
import cc.royalty.domain.PaymentRequest;
import cc.royalty.domain.PaymentRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    public PaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PaymentRun> RUN_MAPPER = (rs, n) -> new PaymentRun(
            rs.getLong("id"), rs.getString("run_no"), rs.getLong("settlement_batch_id"),
            rs.getString("currency"), rs.getString("status"), rs.getString("created_by"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getString("run_kind"), (Long) rs.getObject("account_id"));

    private static final RowMapper<PaymentRequest> REQ_MAPPER = (rs, n) -> new PaymentRequest(
            rs.getLong("id"), rs.getLong("payment_run_id"), (Long) rs.getObject("payable_id"),
            rs.getLong("payee_id"), rs.getString("request_id"), rs.getString("account_snapshot"),
            rs.getString("body_fingerprint"), rs.getLong("amount_minor"), rs.getString("currency"),
            rs.getString("status"), rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("confirmed_at", java.time.OffsetDateTime.class),
            (Long) rs.getObject("escrow_entry_id"), (Long) rs.getObject("arbitration_id"));

    private static final RowMapper<PaymentReceipt> RECEIPT_MAPPER = (rs, n) -> new PaymentReceipt(
            rs.getLong("id"), rs.getString("receipt_ref"), rs.getString("request_id_ref"),
            rs.getString("account_ref"), rs.getLong("amount_minor"), rs.getString("currency"),
            rs.getString("source_file"), rs.getString("match_status"), rs.getString("mismatch_reason"));

    private static final RowMapper<AdjustmentOrder> ADJ_MAPPER = (rs, n) -> new AdjustmentOrder(
            rs.getLong("id"), rs.getString("adjustment_no"), rs.getLong("source_settlement_batch_id"),
            (Long) rs.getObject("new_settlement_batch_id"), rs.getString("reason"), rs.getString("status"));

    // ---------- 付款批次/请求/文件 ----------

    public long insertRun(String runNo, long settlementBatchId, String currency, String actor) {
        return jdbc.queryForObject("insert into payment_run(run_no, settlement_batch_id, currency, status, created_by) "
                + "values (?,?,?, 'GENERATED', ?) returning id",
                Long.class, runNo, settlementBatchId, currency, actor);
    }

    /** 托管付款运行：与托管账户绑定，run_kind=ESCROW；批次列取首笔冻结来源批次 */
    public long insertEscrowRun(String runNo, long settlementBatchId, long accountId,
                                String currency, String actor) {
        return jdbc.queryForObject("insert into payment_run(run_no, settlement_batch_id, currency, status, "
                + "created_by, run_kind, account_id) values (?,?,?, 'GENERATED', ?, 'ESCROW', ?) returning id",
                Long.class, runNo, settlementBatchId, currency, actor, accountId);
    }

    public Optional<PaymentRun> findRunByNo(String no) {
        return jdbc.query("select * from payment_run where run_no=?", RUN_MAPPER, no).stream().findFirst();
    }

    public List<PaymentRun> runsOfBatch(long batchId) {
        return jdbc.query("select * from payment_run where settlement_batch_id=? order by id", RUN_MAPPER, batchId);
    }

    public void updateRunStatus(long runId, String status) {
        jdbc.update("update payment_run set status=? where id=?", status, runId);
    }

    public long insertRequest(long runId, long payableId, long payeeId, String requestId,
                              String accountSnapshot, String bodyFingerprint, long amount, String currency) {
        return jdbc.queryForObject("insert into payment_request(payment_run_id, payable_id, payee_id, request_id, "
                + "account_snapshot, body_fingerprint, amount_minor, currency, status) "
                + "values (?,?,?,?,?,?,?,?,'ISSUED') returning id",
                Long.class, runId, payableId, payeeId, requestId, accountSnapshot,
                bodyFingerprint, amount, currency);
    }

    /** 托管付款请求：绑定托管释放分录与裁决；payable/entry 可空（纯出账指令） */
    public long insertEscrowRequest(long runId, Long payableId, long payeeId, String requestId,
                                    String accountSnapshot, String bodyFingerprint, long amount,
                                    String currency, Long escrowEntryId, Long arbitrationId) {
        return jdbc.queryForObject("""
                insert into payment_request(payment_run_id, payable_id, payee_id, request_id,
                    account_snapshot, body_fingerprint, amount_minor, currency, status,
                    escrow_entry_id, arbitration_id)
                values (?,?,?,?,?,?,?,?,'ISSUED',?,?) returning id""",
                Long.class, runId, payableId, payeeId, requestId, accountSnapshot,
                bodyFingerprint, amount, currency, escrowEntryId, arbitrationId);
    }

    public Optional<PaymentRequest> findRequestByRequestId(String requestId) {
        return jdbc.query("select * from payment_request where request_id=?", REQ_MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<PaymentRequest> findRequestById(long id) {
        return jdbc.query("select * from payment_request where id=?", REQ_MAPPER, id).stream().findFirst();
    }

    public List<PaymentRequest> requestsOfRun(long runId) {
        return jdbc.query("select * from payment_request where payment_run_id=? order by id", REQ_MAPPER, runId);
    }

    public List<PaymentRequest> requestsByArbitration(long arbitrationId) {
        return jdbc.query("select * from payment_request where arbitration_id=? order by id",
                REQ_MAPPER, arbitrationId);
    }

    public List<PaymentRequest> requestsByPayable(long payableId) {
        return jdbc.query("select * from payment_request where payable_id=? order by id",
                REQ_MAPPER, payableId);
    }

    public List<PaymentRun> escrowRunsOfAccount(long accountId) {
        return jdbc.query("select * from payment_run where account_id=? and run_kind='ESCROW' order by id",
                RUN_MAPPER, accountId);
    }

    public java.util.Optional<PaymentRun> findRunById(long id) {
        return jdbc.query("select * from payment_run where id=?", RUN_MAPPER, id).stream().findFirst();
    }

    public void updateRequestStatus(long id, String status) {
        jdbc.update("update payment_request set status=? where id=?", status, id);
    }

    public void confirmRequest(long id) {
        jdbc.update("update payment_request set status='CONFIRMED', confirmed_at=now() where id=?", id);
    }

    /** 裁决撤回：暂停未确认的托管付款请求，防止其继续出款 */
    public void suspendRequest(long id) {
        jdbc.update("update payment_request set status='SUSPENDED' where id=? "
                + "and status in ('ISSUED','TIMEOUT_QUERIED')", id);
    }

    public void insertFile(long runId, String fileName, String filePath, String fingerprint) {
        jdbc.update("insert into payment_file(payment_run_id, file_name, file_path, content_fingerprint) "
                + "values (?,?,?,?)", runId, fileName, filePath, fingerprint);
    }

    public List<String> filesOfRun(long runId) {
        return jdbc.query("select file_path from payment_file where payment_run_id=? order by id",
                (rs, n) -> rs.getString(1), runId);
    }

    // ---------- 回执 ----------

    public long insertReceipt(PaymentReceipt r) {
        return jdbc.queryForObject("insert into payment_receipt(receipt_ref, request_id_ref, account_ref, "
                + "amount_minor, currency, source_file, match_status, mismatch_reason) "
                + "values (?,?,?,?,?,?,?,?) returning id",
                Long.class, r.receiptRef(), r.requestIdRef(), r.accountRef(), r.amountMinor(),
                r.currency(), r.sourceFile(), r.matchStatus(), r.mismatchReason());
    }

    public Optional<PaymentReceipt> findReceiptByRef(String ref) {
        return jdbc.query("select * from payment_receipt where receipt_ref=?", RECEIPT_MAPPER, ref)
                .stream().findFirst();
    }

    public List<PaymentReceipt> receiptsOfRun(long runId) {
        return jdbc.query("select pr.* from payment_receipt pr join payment_request preq "
                + "on pr.request_id_ref = preq.request_id where preq.payment_run_id=? order by pr.id",
                RECEIPT_MAPPER, runId);
    }

    public List<PaymentReceipt> failedReceipts(Long platformId) {
        // 左连接：UNKNOWN_REQUEST 回执没有对应付款请求/运行，也必须出现在失败清单里
        String sql = """
                select pr.* from payment_receipt pr
                left join payment_request preq on pr.request_id_ref = preq.request_id
                left join payment_run prun on prun.id = preq.payment_run_id
                where pr.match_status <> 'MATCHED'
                  and (?::bigint is null or prun.id is null or prun.settlement_batch_id in
                    (select id from settlement_batch where platform_id = ?))
                order by pr.id""";
        return jdbc.query(sql, RECEIPT_MAPPER, platformId, platformId);
    }

    // ---------- 差额单 ----------

    public long insertAdjustmentOrder(String no, long sourceBatchId, String reason) {
        return jdbc.queryForObject("insert into adjustment_order(adjustment_no, source_settlement_batch_id, "
                + "reason, status) values (?,?,?, 'DRAFT') returning id",
                Long.class, no, sourceBatchId, reason);
    }

    public void insertAdjustmentLine(long orderId, Long payeeId, String rightType, long amount,
                                     Long priorContractId, Long newContractId, String reason) {
        jdbc.update("insert into adjustment_line(adjustment_order_id, payee_id, right_type, amount_minor, "
                + "prior_contract_id, new_contract_id, reason) values (?,?,?,?,?,?,?)",
                orderId, payeeId, rightType, amount, priorContractId, newContractId, reason);
    }

    public List<AdjustmentOrder> adjustmentsOfBatch(long sourceBatchId) {
        return jdbc.query("select * from adjustment_order where source_settlement_batch_id=? order by id",
                ADJ_MAPPER, sourceBatchId);
    }

    public Optional<AdjustmentOrder> findAdjustmentByNo(String no) {
        return jdbc.query("select * from adjustment_order where adjustment_no=?", ADJ_MAPPER, no)
                .stream().findFirst();
    }

    public List<java.util.Map<String, Object>> adjustmentLines(long orderId) {
        return jdbc.query("select * from adjustment_line where adjustment_order_id=? order by id",
                (rs, n) -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("payee_id", rs.getObject("payee_id"));
                    m.put("right_type", rs.getString("right_type"));
                    m.put("amount_minor", rs.getLong("amount_minor"));
                    m.put("prior_contract_id", rs.getObject("prior_contract_id"));
                    m.put("new_contract_id", rs.getObject("new_contract_id"));
                    m.put("reason", rs.getString("reason"));
                    return m;
                }, orderId);
    }

    public void approveAdjustment(long orderId, long newBatchId) {
        jdbc.update("update adjustment_order set status='APPROVED', new_settlement_batch_id=?, approved_at=now() "
                + "where id=?", newBatchId, orderId);
    }
}
