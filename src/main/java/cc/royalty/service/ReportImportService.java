package cc.royalty.service;

import cc.royalty.common.Csv;
import cc.royalty.common.DomainException;
import cc.royalty.common.Fingerprints;
import cc.royalty.domain.Platform;
import cc.royalty.domain.ReportBatch;
import cc.royalty.domain.ReportLine;
import cc.royalty.repo.CatalogRepository;
import cc.royalty.repo.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 平台使用明细导入。
 * 文件首行 #BATCH 声明批次与原始合计，第二行为明细头：
 *   platform_line_no,revision_no,supersedes_line_no,platform_item_code,reported_title,
 *   reported_artist,reported_duration,usage_date,territory,media,usage_count,
 *   gross_minor,fee_minor,net_minor,currency,action
 * action=NORMAL 为正常上报，REVOKE 为使用量撤销（关联原报告行）。
 */
@Service
public class ReportImportService {

    private final ReportRepository reports;
    private final CatalogRepository catalog;

    public ReportImportService(ReportRepository reports, CatalogRepository catalog) {
        this.reports = reports;
        this.catalog = catalog;
    }

    public record ImportResult(Long batchId, String batchRef, int valid, int bad, int dup,
                               String reconciliation, List<String> badReasons) {
    }

    @Transactional
    public ImportResult importReport(String content, String sourceFile) {
        String[] lines = content.split("\\r?\\n");
        if (lines.length < 2 || !lines[0].startsWith("#BATCH")) {
            throw new DomainException("报告文件必须以 #BATCH 批次声明行开头");
        }
        String[] meta = lines[0].substring(1).split(",", -1);
        // 0=BATCH 1=batch_ref 2=platform_code 3=period_start 4=period_end
        // 5=declared_total_count 6=declared_gross 7=declared_fee 8=declared_net 9=currency
        String batchRef = meta[1].trim();
        if (reports.findBatchByRef(batchRef).isPresent()) {
            throw new DomainException("批次已存在，不能重复导入: " + batchRef
                    + "（重传请使用新文件并提高修订号）");
        }
        Platform platform = catalog.findPlatformByCode(meta[2].trim())
                .orElseThrow(() -> new DomainException("平台不存在: " + meta[2]));
        LocalDate periodStart = LocalDate.parse(meta[3].trim());
        LocalDate periodEnd = LocalDate.parse(meta[4].trim());
        Long declaredCount = blank(meta, 5) ? null : Long.parseLong(meta[5].trim());
        Long declaredGross = blank(meta, 6) ? null : Long.parseLong(meta[6].trim());
        Long declaredFee = blank(meta, 7) ? null : Long.parseLong(meta[7].trim());
        Long declaredNet = blank(meta, 8) ? null : Long.parseLong(meta[8].trim());
        String currency = blank(meta, 9) ? platform.homeCurrency() : meta[9].trim();

        long batchId = reports.insertBatch(new ReportBatch(null, batchRef, platform.id(), periodStart,
                periodEnd, sourceFile, declaredCount, declaredGross, declaredFee, declaredNet,
                currency, "RECEIVED", 0, 0, 0, "PENDING", null, null));

        List<Csv.Row> rows = Csv.parse(String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length)));
        int valid = 0, bad = 0, dup = 0;
        List<String> badReasons = new ArrayList<>();
        long parsedCount = 0;
        long parsedNet = 0;

        for (Csv.Row row : rows) {
            try {
                String lineNo = req(row, "platform_line_no");
                int revision = row.get("revision_no") == null ? 1 : Integer.parseInt(row.get("revision_no"));
                String action = row.get("action") == null ? "NORMAL" : row.get("action").toUpperCase();
                String rowCurrency = row.get("currency") == null || row.get("currency").isBlank()
                        ? currency : row.get("currency");

                validateShape(row);
                long gross = nz(row.get("gross_minor"));
                long fee = nz(row.get("fee_minor"));
                long net = nz(row.get("net_minor"));
                // net=0 且无费用时视为“仅报总额、净额按协议费用条款推算”的直接收入行；
                // 只要净额或费用被显式给出，就必须满足 gross - fee = net
                if ((net != 0 || fee != 0) && gross - fee != net) {
                    throw new DomainException("净额(net)必须等于总额(gross)-费用(fee)");
                }

                Optional<ReportLine> prior = reports.findLatestRevision(platform.id(), lineNo);
                String fp = Fingerprints.sha256(row.raw() + "|rev=" + revision);
                if (prior.isPresent() && prior.get().revisionNo() == revision
                        && Fingerprints.sha256(prior.get().rawText() + "|rev=" + revision).equals(fp)) {
                    // 同修订号同内容：原件不覆盖，重复行只计数不落库
                    dup++;
                    continue;
                }
                if (prior.isPresent() && revision <= prior.get().revisionNo()) {
                    // 修订号不大于已收原件：忽略，重复行只计数不落库
                    dup++;
                    continue;
                }

                ReportLine newLine = new ReportLine(null, batchId, lineNo, revision,
                        prior.map(ReportLine::id).orElse(null), req(row, "platform_item_code"),
                        row.get("reported_title"), row.get("reported_artist"),
                        intOrNull(row.get("reported_duration")),
                        LocalDate.parse(req(row, "usage_date")),
                        orDefault(row.get("territory"), "GLOBAL"), req(row, "media"),
                        longOrNull(row.get("usage_count")), gross, fee, net, rowCurrency,
                        row.raw(), "VALID", null,
                        "REVOKE".equals(action) ? "REVOKED" : "NEW", null);
                long newId = reports.insertLine(newLine);

                if (prior.isPresent()) {
                    if ("REVOKE".equals(action)) {
                        // 使用量撤销：关联并撤销原报告行
                        reports.markLineRevoked(prior.get().id());
                    } else {
                        // 晚到更正：原件标记被取代，不覆盖
                        reports.markLineSuperseded(prior.get().id(), newId);
                    }
                } else if ("REVOKE".equals(action)) {
                    reports.markLineRevoked(newId);
                }
                valid++;
                parsedCount++;
                if (!"REVOKE".equals(action)) {
                    parsedNet += net;
                }
            } catch (Exception e) {
                bad++;
                badReasons.add("第" + row.lineNo() + "行: " + e.getMessage());
                reports.insertLine(new ReportLine(null, batchId,
                        row.get("platform_line_no") == null ? "L" + row.lineNo() : row.get("platform_line_no"),
                        1, null, orDefault(row.get("platform_item_code"), "UNKNOWN"),
                        row.get("reported_title"), row.get("reported_artist"),
                        intOrNull(row.get("reported_duration")),
                        dateOrNull(row.get("usage_date")),
                        orDefault(row.get("territory"), "GLOBAL"), orDefault(row.get("media"), "STREAM"),
                        longOrNull(row.get("usage_count")), nz(row.get("gross_minor")),
                        nz(row.get("fee_minor")), nz(row.get("net_minor")), currency,
                        row.raw(), "BAD", e.getMessage(), "NEW", null));
            }
        }

        // 声明合计核对：任一项不符则转核对，不得在漏行时仍称完整
        boolean mismatch = false;
        List<String> mismatchNotes = new ArrayList<>();
        if (declaredCount != null && declaredCount != parsedCount + dup) {
            mismatch = true;
            mismatchNotes.add("声明行数 " + declaredCount + " ≠ 有效 " + parsedCount + " + 重复 " + dup);
        }
        if (declaredNet != null && declaredNet != parsedNet) {
            mismatch = true;
            mismatchNotes.add("声明净额 " + declaredNet + " ≠ 明细现行净额 " + parsedNet);
        }
        if (bad > 0) {
            mismatch = true;
            mismatchNotes.add("存在 " + bad + " 条坏行待人工核对");
        }
        String reconciliation = mismatch ? "MISMATCH" : "MATCHED";
        String status = mismatch ? "RECONCILING" : "ACCEPTED";
        reports.updateParseCounters(batchId, valid, bad, dup, reconciliation, status,
                mismatchNotes.isEmpty() ? null : String.join("; ", mismatchNotes));

        return new ImportResult(batchId, batchRef, valid, bad, dup, reconciliation, badReasons);
    }

    private void validateShape(Csv.Row row) {
        req(row, "platform_item_code");
        LocalDate.parse(req(row, "usage_date"));
        String media = req(row, "media");
        if (!List.of("STREAM", "DOWNLOAD", "BROADCAST").contains(media)) {
            throw new DomainException("media 非法: " + media);
        }
    }

    private static boolean blank(String[] a, int i) {
        return i >= a.length || a[i] == null || a[i].isBlank();
    }

    private static String req(Csv.Row row, String key) {
        String v = row.get(key);
        if (v == null || v.isBlank()) {
            throw new DomainException("缺少字段 " + key);
        }
        return v;
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    private static long nz(String v) {
        return v == null || v.isBlank() ? 0L : Long.parseLong(v);
    }

    private static Long longOrNull(String v) {
        return v == null || v.isBlank() ? null : Long.parseLong(v);
    }

    private static Integer intOrNull(String v) {
        return v == null || v.isBlank() ? null : Integer.valueOf(v);
    }

    private static LocalDate dateOrNull(String v) {
        try {
            return v == null || v.isBlank() ? null : LocalDate.parse(v);
        } catch (Exception e) {
            return null;
        }
    }
}
