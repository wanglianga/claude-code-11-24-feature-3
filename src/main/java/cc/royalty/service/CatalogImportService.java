package cc.royalty.service;

import cc.royalty.common.Csv;
import cc.royalty.common.DomainException;
import cc.royalty.repo.CatalogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 版权目录与合同导入：权利人、作品、贡献者、录音、合同份额、平台协议。
 * 各文件均为带头 CSV；编码冲突（code 已存在）跳过并计数，不覆盖既有目录。
 */
@Service
public class CatalogImportService {

    private final CatalogRepository catalog;

    public CatalogImportService(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public record ImportSummary(String file, int inserted, int skipped, int bad, List<String> errors) {
    }

    @Transactional
    public ImportSummary importPayees(String csv) {
        int inserted = 0, skipped = 0, bad = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Csv.Row row : Csv.parse(csv)) {
            try {
                String code = req(row, "payee_code");
                String name = req(row, "payee_name");
                String type = req(row, "payee_type");
                String account = row.get("bank_account");
                String currency = orDefault(row.get("currency"), "USD");
                if (catalog.findPayeeByCode(code).isPresent()) {
                    skipped++;
                    continue;
                }
                catalog.insertPayee(code, name, type, account, currency);
                inserted++;
            } catch (Exception e) {
                bad++;
                errors.add("第" + row.lineNo() + "行: " + e.getMessage());
            }
        }
        return new ImportSummary("payees", inserted, skipped, bad, errors);
    }

    @Transactional
    public ImportSummary importWorks(String csv) {
        int inserted = 0, skipped = 0, bad = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Csv.Row row : Csv.parse(csv)) {
            try {
                String code = req(row, "work_code");
                if (catalog.findWorkByCode(code).isPresent()) {
                    skipped++;
                    continue;
                }
                catalog.insertWork(code, req(row, "title"), row.get("iswc"));
                inserted++;
            } catch (Exception e) {
                bad++;
                errors.add("第" + row.lineNo() + "行: " + e.getMessage());
            }
        }
        return new ImportSummary("works", inserted, skipped, bad, errors);
    }

    @Transactional
    public ImportSummary importContributors(String csv) {
        int inserted = 0, skipped = 0, bad = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Csv.Row row : Csv.parse(csv)) {
            try {
                long workId = catalog.findWorkByCode(req(row, "work_code"))
                        .orElseThrow(() -> new DomainException("作品不存在")).id();
                Long payeeId = row.get("payee_code") == null ? null
                        : catalog.findPayeeByCode(row.get("payee_code"))
                        .map(p -> p.id()).orElse(null);
                catalog.insertContributor(workId, payeeId, req(row, "contributor_name"), req(row, "role"));
                inserted++;
            } catch (Exception e) {
                bad++;
                errors.add("第" + row.lineNo() + "行: " + e.getMessage());
            }
        }
        return new ImportSummary("contributors", inserted, skipped, bad, errors);
    }

    @Transactional
    public ImportSummary importRecordings(String csv) {
        int inserted = 0, skipped = 0, bad = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Csv.Row row : Csv.parse(csv)) {
            try {
                String code = req(row, "recording_code");
                if (catalog.findRecordingByCode(code).isPresent()) {
                    skipped++;
                    continue;
                }
                long workId = catalog.findWorkByCode(req(row, "work_code"))
                        .orElseThrow(() -> new DomainException("作品不存在")).id();
                Integer duration = row.get("duration_seconds") == null ? null
                        : Integer.valueOf(row.get("duration_seconds"));
                Long ownerId = null;
                if (row.get("owner_payee_code") != null) {
                    ownerId = catalog.findPayeeByCode(row.get("owner_payee_code"))
                            .orElseThrow(() -> new DomainException("权利人不存在")).id();
                }
                LocalDate release = row.get("first_release_date") == null ? null
                        : LocalDate.parse(row.get("first_release_date"));
                catalog.insertRecording(code, workId, req(row, "version_title"), duration, ownerId, release);
                inserted++;
            } catch (Exception e) {
                bad++;
                errors.add("第" + row.lineNo() + "行: " + e.getMessage());
            }
        }
        return new ImportSummary("recordings", inserted, skipped, bad, errors);
    }

    @Transactional
    public ImportSummary importPlatforms(String csv) {
        int inserted = 0, skipped = 0, bad = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Csv.Row row : Csv.parse(csv)) {
            try {
                String code = req(row, "platform_code");
                if (catalog.findPlatformByCode(code).isPresent()) {
                    skipped++;
                    continue;
                }
                catalog.insertPlatform(code, req(row, "platform_name"), orDefault(row.get("currency"), "USD"));
                inserted++;
            } catch (Exception e) {
                bad++;
                errors.add("第" + row.lineNo() + "行: " + e.getMessage());
            }
        }
        return new ImportSummary("platforms", inserted, skipped, bad, errors);
    }

    /**
     * 合同文件每行是一份合同下的一条份额；同 contract_code 的首行建立合同头，
     * 后续行追加份额。导入时即校验单合同份额合计不超过 1（超出直接判坏行并拒绝该合同）。
     */
    @Transactional
    public ImportSummary importContracts(String csv) {
        int inserted = 0, skipped = 0, bad = 0;
        List<String> errors = new java.util.ArrayList<>();
        Map<String, Long> contractIdCache = new HashMap<>();
        Map<String, cc.royalty.common.Rational> sums = new HashMap<>();

        for (Csv.Row row : Csv.parse(csv)) {
            try {
                String contractCode = req(row, "contract_code");
                // 仅跳过本次导入之前就已存在的外部合同；本文件首行刚建的合同在 cache 中，后续份额行必须继续
                if (!contractIdCache.containsKey(contractCode)
                        && catalog.findContractByCode(contractCode).isPresent()) {
                    skipped++;
                    continue;
                }
                long contractId = contractIdCache.computeIfAbsent(contractCode, c -> {
                    String rightType = req(row, "right_type");
                    String refCode = req(row, "ref_code");
                    Long workId = null;
                    Long recordingId = null;
                    if ("PUBLISHING".equals(rightType)) {
                        workId = catalog.findWorkByCode(refCode)
                                .orElseThrow(() -> new DomainException("作品不存在: " + refCode)).id();
                    } else {
                        recordingId = catalog.findRecordingByCode(refCode)
                                .orElseThrow(() -> new DomainException("录音不存在: " + refCode)).id();
                    }
                    LocalDate to = row.get("valid_to") == null || row.get("valid_to").isBlank()
                            ? null : LocalDate.parse(row.get("valid_to"));
                    return catalog.insertContract(c, workId, recordingId, rightType,
                            orDefault(row.get("territory"), "GLOBAL"), orDefault(row.get("media"), "ALL"),
                            LocalDate.parse(req(row, "valid_from")), to);
                });
                long num = Long.parseLong(req(row, "share_num"));
                long den = Long.parseLong(req(row, "share_den"));
                if (num <= 0 || den <= 0 || num > den) {
                    throw new DomainException("份额非法: " + num + "/" + den);
                }
                cc.royalty.common.Rational newSum = sums
                        .getOrDefault(contractCode, cc.royalty.common.Rational.ZERO)
                        .add(cc.royalty.common.Rational.of(num, den));
                if (newSum.compareTo(cc.royalty.common.Rational.ONE) > 0) {
                    throw new DomainException("合同 " + contractCode + " 份额合计超过 100%: " + newSum);
                }
                sums.put(contractCode, newSum);
                long payeeId = catalog.findPayeeByCode(req(row, "payee_code"))
                        .orElseThrow(() -> new DomainException("权利人不存在")).id();
                catalog.insertShare(contractId, payeeId, row.get("role"), num, den);
                inserted++;
            } catch (Exception e) {
                bad++;
                errors.add("第" + row.lineNo() + "行: " + e.getMessage());
            }
        }
        return new ImportSummary("contracts", inserted, skipped, bad, errors);
    }

    @Transactional
    public ImportSummary importAgreements(String csv) {
        int inserted = 0, skipped = 0, bad = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Csv.Row row : Csv.parse(csv)) {
            try {
                long platformId = catalog.findPlatformByCode(req(row, "platform_code"))
                        .orElseThrow(() -> new DomainException("平台不存在")).id();
                String method = req(row, "settlement_method");
                BigDecimal rate = row.get("rate_minor") == null || row.get("rate_minor").isBlank()
                        ? null : new BigDecimal(row.get("rate_minor"));
                if ("PER_UNIT".equals(method) && rate == null) {
                    throw new DomainException("按次计费必须提供 rate_minor");
                }
                long feeNum = row.get("fee_num") == null ? 0 : Long.parseLong(row.get("fee_num"));
                long feeDen = row.get("fee_den") == null ? 1 : Long.parseLong(row.get("fee_den"));
                long budNum = row.get("budget_num") == null ? 1 : Long.parseLong(row.get("budget_num"));
                long budDen = row.get("budget_den") == null ? 1 : Long.parseLong(row.get("budget_den"));
                LocalDate to = row.get("valid_to") == null || row.get("valid_to").isBlank()
                        ? null : LocalDate.parse(row.get("valid_to"));
                catalog.insertAgreement(new cc.royalty.domain.PlatformAgreement(
                        null, platformId, req(row, "right_type"),
                        orDefault(row.get("territory"), "GLOBAL"), orDefault(row.get("media"), "ALL"),
                        method, rate, orDefault(row.get("currency"), "USD"),
                        orDefault(row.get("fee_basis"), "NONE"), feeNum, feeDen, budNum, budDen,
                        LocalDate.parse(req(row, "valid_from")), to, "ACTIVE"));
                inserted++;
            } catch (Exception e) {
                bad++;
                errors.add("第" + row.lineNo() + "行: " + e.getMessage());
            }
        }
        return new ImportSummary("agreements", inserted, skipped, bad, errors);
    }

    /** 预置已审核映射（演示数据用；业务上的人工采用走 MappingService） */
    @Transactional
    public ImportSummary importMappings(String csv) {
        int inserted = 0, skipped = 0, bad = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (Csv.Row row : Csv.parse(csv)) {
            try {
                long platformId = catalog.findPlatformByCode(req(row, "platform_code"))
                        .orElseThrow(() -> new DomainException("平台不存在")).id();
                String item = req(row, "platform_item_code");
                if (catalog.findActiveMapping(platformId, item).isPresent()) {
                    skipped++;
                    continue;
                }
                long recordingId = catalog.findRecordingByCode(req(row, "recording_code"))
                        .orElseThrow(() -> new DomainException("录音不存在")).id();
                long id = catalog.insertProposedMapping(platformId, item, recordingId, null);
                if ("APPROVED".equals(orDefault(row.get("status"), "APPROVED"))) {
                    catalog.approveMapping(id, "seed-import", recordingId);
                }
                inserted++;
            } catch (Exception e) {
                bad++;
                errors.add("第" + row.lineNo() + "行: " + e.getMessage());
            }
        }
        return new ImportSummary("mappings", inserted, skipped, bad, errors);
    }

    private static String req(Csv.Row row, String key) {
        String v = row.get(key);
        if (v == null || v.isBlank()) {
            throw new DomainException("缺少字段 " + key);
        }
        return v;
    }

    private static String orDefault(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v;
    }
}
