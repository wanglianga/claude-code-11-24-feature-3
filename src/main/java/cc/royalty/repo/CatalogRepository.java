package cc.royalty.repo;

import cc.royalty.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 权利人 / 作品 / 录音 / 平台 ----------

    private static final RowMapper<Payee> PAYEE_MAPPER = (rs, n) -> new Payee(
            rs.getLong("id"), rs.getString("payee_code"), rs.getString("payee_name"),
            rs.getString("payee_type"), rs.getString("bank_account"),
            rs.getString("account_currency"), rs.getString("status"));

    private static final RowMapper<SoundRecording> REC_MAPPER = (rs, n) -> new SoundRecording(
            rs.getLong("id"), rs.getString("recording_code"), rs.getLong("work_id"),
            rs.getString("version_title"), (Integer) rs.getObject("duration_seconds"),
            (Long) rs.getObject("owner_payee_id"), rs.getObject("first_release_date", LocalDate.class),
            rs.getString("status"));

    private static final RowMapper<Work> WORK_MAPPER = (rs, n) -> new Work(
            rs.getLong("id"), rs.getString("work_code"), rs.getString("title"), rs.getString("iswc"));

    private static final RowMapper<Contract> CONTRACT_MAPPER = (rs, n) -> new Contract(
            rs.getLong("id"), rs.getString("contract_code"), (Long) rs.getObject("work_id"),
            (Long) rs.getObject("recording_id"), rs.getString("right_type"), rs.getString("territory"),
            rs.getString("media"), rs.getDate("valid_from").toLocalDate(),
            rs.getDate("valid_to") == null ? null : rs.getDate("valid_to").toLocalDate(),
            rs.getString("status"));

    private static final RowMapper<ContractShare> SHARE_MAPPER = (rs, n) -> new ContractShare(
            rs.getLong("id"), rs.getLong("contract_id"), rs.getLong("payee_id"),
            rs.getString("role"), rs.getLong("share_numerator"), rs.getLong("share_denominator"));

    private static final RowMapper<PlatformAgreement> AGR_MAPPER = (rs, n) -> new PlatformAgreement(
            rs.getLong("id"), rs.getLong("platform_id"), rs.getString("right_type"),
            rs.getString("territory"), rs.getString("media"), rs.getString("settlement_method"),
            rs.getBigDecimal("rate_minor"), rs.getString("currency"), rs.getString("fee_basis"),
            rs.getLong("fee_numerator"), rs.getLong("fee_denominator"),
            rs.getLong("budget_numerator"), rs.getLong("budget_denominator"),
            rs.getDate("valid_from").toLocalDate(),
            rs.getDate("valid_to") == null ? null : rs.getDate("valid_to").toLocalDate(),
            rs.getString("status"));

    private static final RowMapper<PlatformMapping> MAPPING_MAPPER = (rs, n) -> new PlatformMapping(
            rs.getLong("id"), rs.getLong("platform_id"), rs.getString("platform_item_code"),
            (Long) rs.getObject("recording_id"), rs.getString("status"),
            (Long) rs.getObject("adopted_candidate_id"), rs.getString("approved_by"),
            rs.getObject("approved_at", java.time.OffsetDateTime.class),
            rs.getObject("revoked_at", java.time.OffsetDateTime.class), rs.getString("revoke_reason"));

    public Optional<Payee> findPayeeByCode(String code) {
        return jdbc.query("select * from payee where payee_code = ?", PAYEE_MAPPER, code)
                .stream().findFirst();
    }

    public Optional<Payee> findPayeeById(long id) {
        return jdbc.query("select * from payee where id = ?", PAYEE_MAPPER, id)
                .stream().findFirst();
    }

    public List<Payee> allPayees() {
        return jdbc.query("select * from payee order by id", PAYEE_MAPPER);
    }

    public void insertPayee(String code, String name, String type, String account, String currency) {
        jdbc.update("insert into payee(payee_code, payee_name, payee_type, bank_account, account_currency) "
                + "values (?,?,?,?,?)", code, name, type, account, currency);
    }

    public Optional<Work> findWorkByCode(String code) {
        return jdbc.query("select * from work where work_code = ?", WORK_MAPPER, code)
                .stream().findFirst();
    }

    public Optional<Work> findWorkById(long id) {
        return jdbc.query("select * from work where id = ?", WORK_MAPPER, id)
                .stream().findFirst();
    }

    public void insertWork(String code, String title, String iswc) {
        jdbc.update("insert into work(work_code, title, iswc) values (?,?,?)", code, title, iswc);
    }

    public void insertContributor(long workId, Long payeeId, String name, String role) {
        jdbc.update("insert into work_contributor(work_id, payee_id, contributor_name, role) values (?,?,?,?)",
                workId, payeeId, name, role);
    }

    public List<WorkContributor> contributorsOfWork(long workId) {
        return jdbc.query("select * from work_contributor where work_id = ? order by id",
                (rs, n) -> new WorkContributor(rs.getLong("id"), rs.getLong("work_id"),
                        (Long) rs.getObject("payee_id"), rs.getString("contributor_name"),
                        rs.getString("role")), workId);
    }

    public Optional<SoundRecording> findRecordingByCode(String code) {
        return jdbc.query("select * from sound_recording where recording_code = ?", REC_MAPPER, code)
                .stream().findFirst();
    }

    public Optional<SoundRecording> findRecordingById(long id) {
        return jdbc.query("select * from sound_recording where id = ?", REC_MAPPER, id)
                .stream().findFirst();
    }

    public List<SoundRecording> recordingsOfWork(long workId) {
        return jdbc.query("select * from sound_recording where work_id = ? order by id", REC_MAPPER, workId);
    }

    public List<SoundRecording> allRecordings() {
        return jdbc.query("select * from sound_recording order by id", REC_MAPPER);
    }

    public void insertRecording(String code, long workId, String versionTitle, Integer duration,
                                Long ownerPayeeId, LocalDate releaseDate) {
        jdbc.update("insert into sound_recording(recording_code, work_id, version_title, duration_seconds, "
                + "owner_payee_id, first_release_date) values (?,?,?,?,?,?)",
                code, workId, versionTitle, duration, ownerPayeeId, releaseDate);
    }

    public Optional<Platform> findPlatformByCode(String code) {
        return jdbc.query("select * from platform where platform_code = ?",
                (rs, n) -> new Platform(rs.getLong("id"), rs.getString("platform_code"),
                        rs.getString("platform_name"), rs.getString("home_currency")), code)
                .stream().findFirst();
    }

    public List<Platform> allPlatforms() {
        return jdbc.query("select * from platform order by id",
                (rs, n) -> new Platform(rs.getLong("id"), rs.getString("platform_code"),
                        rs.getString("platform_name"), rs.getString("home_currency")));
    }

    public void insertPlatform(String code, String name, String currency) {
        jdbc.update("insert into platform(platform_code, platform_name, home_currency) values (?,?,?)",
                code, name, currency);
    }

    // ---------- 平台映射（归属唯一确定性依据；含撤回历史） ----------

    /** 结算可采信的映射：必须是已审核 APPROVED；PROPOSED/REVOKED 都不能作为归属依据 */
    public Optional<PlatformMapping> findActiveMapping(long platformId, String itemCode) {
        return jdbc.query("select * from platform_mapping where platform_id = ? and platform_item_code = ? "
                + "and status = 'APPROVED' order by id desc limit 1", MAPPING_MAPPER, platformId, itemCode)
                .stream().findFirst();
    }

    public Optional<PlatformMapping> findMappingById(long id) {
        return jdbc.query("select * from platform_mapping where id = ?", MAPPING_MAPPER, id)
                .stream().findFirst();
    }

    public List<PlatformMapping> mappingsByStatus(String status) {
        return jdbc.query("select * from platform_mapping where status = ? order by id", MAPPING_MAPPER, status);
    }

    public List<PlatformMapping> allMappings() {
        return jdbc.query("select * from platform_mapping order by id", MAPPING_MAPPER);
    }

    public long insertProposedMapping(long platformId, String itemCode, Long recordingId,
                                      Long adoptedCandidateId) {
        return jdbc.queryForObject("insert into platform_mapping(platform_id, platform_item_code, "
                + "recording_id, status, adopted_candidate_id) values (?,?,?, 'PROPOSED', ?) returning id",
                Long.class, platformId, itemCode, recordingId, adoptedCandidateId);
    }

    public void approveMapping(long mappingId, String actor, Long recordingId) {
        jdbc.update("update platform_mapping set status = 'APPROVED', recording_id = ?, approved_by = ?, "
                + "approved_at = now() where id = ?", recordingId, actor, mappingId);
        jdbc.update("insert into platform_mapping_history(mapping_id, old_status, new_status, recording_id, actor) "
                + "values (?, 'PROPOSED', 'APPROVED', ?, ?)", mappingId, recordingId, actor);
    }

    public void rejectProposedMapping(long mappingId, String actor) {
        jdbc.update("insert into platform_mapping_history(mapping_id, old_status, new_status, actor) "
                + "values (?, 'PROPOSED', 'REJECTED', ?)", mappingId, actor);
        jdbc.update("delete from platform_mapping where id = ? and status = 'PROPOSED'", mappingId);
    }

    public void revokeMapping(long mappingId, String actor, String reason) {
        PlatformMapping m = findMappingById(mappingId)
                .orElseThrow(() -> new IllegalArgumentException("映射不存在: " + mappingId));
        jdbc.update("update platform_mapping set status = 'REVOKED', revoked_at = now(), revoke_reason = ? "
                + "where id = ?", reason, mappingId);
        jdbc.update("insert into platform_mapping_history(mapping_id, old_status, new_status, recording_id, "
                + "reason, actor) values (?, 'APPROVED', 'REVOKED', ?, ?, ?)",
                mappingId, m.recordingId(), reason, actor);
    }

    // ---------- 合同与份额 ----------

    public long insertContract(String code, Long workId, Long recordingId, String rightType,
                               String territory, String media, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("insert into contract(contract_code, work_id, recording_id, right_type, "
                + "territory, media, valid_from, valid_to) values (?,?,?,?,?,?,?,?) returning id",
                Long.class, code, workId, recordingId, rightType, territory, media, from, to);
    }

    public Optional<Contract> findContractByCode(String code) {
        return jdbc.query("select * from contract where contract_code = ?", CONTRACT_MAPPER, code)
                .stream().findFirst();
    }

    public void insertShare(long contractId, long payeeId, String role, long num, long den) {
        jdbc.update("insert into contract_share(contract_id, payee_id, role, share_numerator, share_denominator) "
                + "values (?,?,?,?,?)", contractId, payeeId, role, num, den);
    }

    public List<ContractShare> sharesOfContract(long contractId) {
        return jdbc.query("select * from contract_share where contract_id = ? order by id",
                SHARE_MAPPER, contractId);
    }

    public Optional<Contract> findContractById(long id) {
        return jdbc.query("select * from contract where id = ?", CONTRACT_MAPPER, id)
                .stream().findFirst();
    }

    public List<Contract> allContracts() {
        return jdbc.query("select * from contract order by id", CONTRACT_MAPPER);
    }

    /**
     * 按使用日期选取生效合同：状态 ACTIVE、日期在有效区间、地区与媒介匹配
     * （GLOBAL/ALL 为通配）。出版权挂作品，录音权挂录音。
     */
    public List<Contract> effectiveContracts(String rightType, long refId, LocalDate date,
                                             String territory, String media) {
        String refColumn = "PUBLISHING".equals(rightType) ? "work_id" : "recording_id";
        String sql = "select * from contract where right_type = ? and " + refColumn + " = ? "
                + "and status = 'ACTIVE' and valid_from <= ? and (valid_to is null or valid_to >= ?) "
                + "and (territory = 'GLOBAL' or territory = ?) "
                + "and (media = 'ALL' or media = ?) order by id";
        return jdbc.query(sql, CONTRACT_MAPPER, rightType, refId, date, date, territory, media);
    }

    // ---------- 平台协议 ----------

    public long insertAgreement(PlatformAgreement a) {
        return jdbc.queryForObject("insert into platform_agreement(platform_id, right_type, territory, media, "
                + "settlement_method, rate_minor, currency, fee_basis, fee_numerator, fee_denominator, "
                + "budget_numerator, budget_denominator, valid_from, valid_to) "
                + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?) returning id",
                Long.class, a.platformId(), a.rightType(), a.territory(), a.media(),
                a.settlementMethod(), a.rateMinor(), a.currency(), a.feeBasis(),
                a.feeNumerator(), a.feeDenominator(), a.budgetNumerator(), a.budgetDenominator(),
                a.validFrom(), a.validTo());
    }

    public List<PlatformAgreement> effectiveAgreements(long platformId, String rightType,
                                                        LocalDate date, String territory, String media) {
        String sql = "select * from platform_agreement where platform_id = ? and right_type = ? "
                + "and status = 'ACTIVE' and valid_from <= ? and (valid_to is null or valid_to >= ?) "
                + "and (territory = 'GLOBAL' or territory = ?) "
                + "and (media = 'ALL' or media = ?) order by id";
        return jdbc.query(sql, AGR_MAPPER, platformId, rightType, date, date, territory, media);
    }
}
