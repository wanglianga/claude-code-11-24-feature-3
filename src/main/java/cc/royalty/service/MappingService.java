package cc.royalty.service;

import cc.royalty.common.DomainException;
import cc.royalty.domain.*;
import cc.royalty.repo.CatalogRepository;
import cc.royalty.repo.ReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * 归属匹配：
 * 标题、作者、时长只生成候选（mapping_candidate），任何相似度都不能自动归属；
 * 只有经人工采用并审核的 platform_mapping 才是结算时认可的录音标识。
 * 采用前可预览影响金额（不落库）；映射可撤回，撤回后历史结算在复核时被拦截。
 */
@Service
public class MappingService {

    private final ReportRepository reports;
    private final CatalogRepository catalog;
    private final IncomeCalculator incomeCalculator;
    private final ShareResolver shareResolver;

    public MappingService(ReportRepository reports, CatalogRepository catalog,
                          IncomeCalculator incomeCalculator, ShareResolver shareResolver) {
        this.reports = reports;
        this.catalog = catalog;
        this.incomeCalculator = incomeCalculator;
        this.shareResolver = shareResolver;
    }

    // ---------- 候选生成 ----------

    @Transactional
    public Map<String, Object> generateCandidates(long batchId) {
        ReportBatch batch = reports.findBatchById(batchId)
                .orElseThrow(() -> new DomainException("批次不存在: " + batchId));
        int created = 0;
        List<Map<String, Object>> sample = new ArrayList<>();
        for (ReportLine line : reports.linesOfBatch(batchId)) {
            if (!"VALID".equals(line.parseStatus())) {
                continue;
            }
            Optional<PlatformMapping> existing = catalog.findActiveMapping(batch.platformId(), line.platformItemCode());
            List<MappingCandidate> already = reports.candidatesOfLine(line.id());
            if (existing.isPresent() || !already.isEmpty()) {
                continue;
            }
            List<Scored> scored = scoreRecordings(line);
            for (Scored s : scored.stream().limit(5).toList()) {
                reports.insertCandidate(line.id(), s.recordingId(), BigDecimal.valueOf(s.score),
                        BigDecimal.valueOf(s.titleSim), s.contributorMatch, s.durationDiff, s.basis);
                created++;
            }
            if (sample.size() < 10 && !scored.isEmpty()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("report_line_id", line.id());
                m.put("platform_item_code", line.platformItemCode());
                m.put("top_candidates", scored.stream().limit(3).map(x -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("recording_id", x.recordingId());
                    c.put("score", round(x.score()));
                    c.put("basis", x.basis());
                    return c;
                }).toList());
                sample.add(m);
            }
        }
        return Map.of("candidates_created", created, "sample", sample);
    }

    private record Scored(long recordingId, double score, double titleSim, boolean contributorMatch,
                          Integer durationDiff, String basis) {
    }

    private List<Scored> scoreRecordings(ReportLine line) {
        List<Scored> out = new ArrayList<>();
        for (SoundRecording rec : catalog.allRecordings()) {
            Work work = catalog.findRecordingById(rec.id()).flatMap(r -> catalog.findWorkById(r.workId())).orElse(null);
            double titleSim = Math.max(
                    Similarity.titleSimilarity(line.reportedTitle(), rec.versionTitle()),
                    work == null ? 0 : Similarity.titleSimilarity(line.reportedTitle(), work.title()));
            List<String> catalogNames = new ArrayList<>();
            if (work != null) {
                for (WorkContributor wc : catalog.contributorsOfWork(work.id())) {
                    catalogNames.add(wc.contributorName());
                }
            }
            if (rec.ownerPayeeId() != null) {
                catalog.findPayeeById(rec.ownerPayeeId()).ifPresent(p -> catalogNames.add(p.payeeName()));
            }
            boolean contributorMatch = Similarity.nameMatches(line.reportedArtist(),
                    catalogNames.toArray(new String[0]));
            Integer durationDiff = null;
            double durSim = 0;
            if (line.reportedDuration() != null && rec.durationSeconds() != null) {
                durationDiff = Math.abs(line.reportedDuration() - rec.durationSeconds());
                durSim = Math.max(0, 1 - durationDiff / 30.0);
            }
            // 仅对可得分量计权平均；标题是必要分量
            double score;
            if (line.reportedDuration() != null && rec.durationSeconds() != null) {
                score = 0.6 * titleSim + 0.25 * (contributorMatch ? 1 : 0) + 0.15 * durSim;
            } else {
                score = 0.7 * titleSim + 0.3 * (contributorMatch ? 1 : 0);
            }
            if (score < 0.30) {
                continue;
            }
            String basis = (titleSim > 0.6 ? "TITLE" : "TITLE_WEAK")
                    + (contributorMatch ? "+ARTIST" : "")
                    + (durationDiff != null ? "+DURATION" : "");
            out.add(new Scored(rec.id(), score, titleSim, contributorMatch, durationDiff, basis));
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed());
        return out;
    }

    // ---------- 待匹配清单 ----------

    public List<Map<String, Object>> pendingMatchings(Long batchId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<ReportBatch> batches = batchId == null ? reports.allBatches()
                : reports.findBatchById(batchId).map(List::of).orElse(List.of());
        for (ReportBatch b : batches) {
            Set<String> seen = new HashSet<>();
            for (ReportLine line : reports.linesOfBatch(b.id())) {
                if (!"VALID".equals(line.parseStatus()) || !seen.add(line.platformItemCode())) {
                    continue;
                }
                if (catalog.findActiveMapping(b.platformId(), line.platformItemCode()).isPresent()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("batch_ref", b.batchRef());
                m.put("platform_item_code", line.platformItemCode());
                m.put("reported_title", line.reportedTitle());
                m.put("reported_artist", line.reportedArtist());
                m.put("candidates", reports.candidatesOfLine(line.id()).stream().map(c -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("candidate_id", c.id());
                    cm.put("recording_id", c.recordingId());
                    cm.put("score", c.score());
                    cm.put("basis", c.basis());
                    cm.put("status", c.status());
                    return cm;
                }).toList());
                out.add(m);
            }
        }
        return out;
    }

    // ---------- 采用前预览（不落库） ----------

    public Map<String, Object> previewAdoption(long candidateId) {
        MappingCandidate candidate = reports.findCandidateById(candidateId)
                .orElseThrow(() -> new DomainException("候选不存在: " + candidateId));
        ReportLine line = reports.findLineById(candidate.reportLineId())
                .orElseThrow(() -> new DomainException("报告行不存在"));
        ReportBatch batch = reports.findBatchById(line.batchId()).orElseThrow();
        SoundRecording recording = catalog.findRecordingById(candidate.recordingId()).orElseThrow();
        Work work = catalog.findWorkById(recording.workId()).orElseThrow();
        return buildPreview(batch.platformId(), line.platformItemCode(), recording, work);
    }

    public Map<String, Object> previewManualMapping(long platformId, String itemCode, long recordingId) {
        SoundRecording recording = catalog.findRecordingById(recordingId)
                .orElseThrow(() -> new DomainException("录音不存在"));
        Work work = catalog.findWorkById(recording.workId()).orElseThrow();
        return buildPreview(platformId, itemCode, recording, work);
    }

    private Map<String, Object> buildPreview(long platformId, String itemCode,
                                             SoundRecording recording, Work work) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("platform_item_code", itemCode);
        preview.put("would_map_to_recording", recording.recordingCode());
        preview.put("work", work.title());
        boolean currentlyMapped = catalog.findActiveMapping(platformId, itemCode).isPresent();
        preview.put("currently_has_approved_mapping", currentlyMapped);

        List<ReportLine> lines = reports.activeLinesByItem(itemCode);
        long totalDistributable = 0;
        long wouldAllocate = 0;
        long wouldHold = 0;
        long wouldUnclaim = 0;
        List<Map<String, Object>> lineViews = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        for (ReportLine line : lines) {
            IncomeCalculator.LineIncome income = incomeCalculator.determine(line, platformId);
            blockers.addAll(income.problems());
            Map<String, Object> lv = new LinkedHashMap<>();
            lv.put("report_line_id", line.id());
            lv.put("usage_date", line.usageDate().toString());
            lv.put("income", income);
            List<Map<String, Object>> rightViews = new ArrayList<>();
            for (IncomeCalculator.RightBudget rb : income.budgets()) {
                Long wid = "PUBLISHING".equals(rb.rightType()) ? work.id() : null;
                Long rid = "RECORDING".equals(rb.rightType()) ? recording.id() : null;
                ShareResolver.Resolution r = shareResolver.resolve(rb.rightType(), wid, rid,
                        line.usageDate(), line.territory(), line.media(), rb.distributableMinor());
                blockers.addAll(r.reasons());
                Map<String, Object> rv = new LinkedHashMap<>();
                rv.put("right_type", rb.rightType());
                rv.put("budget_minor", rb.distributableMinor());
                rv.put("allocated_minor", r.allocatedMinor());
                rv.put("conflict_hold_minor", r.conflictHoldMinor());
                rv.put("unclaimed_minor", r.unclaimedMinor());
                rv.put("payees", r.payees().stream().map(p -> {
                    Map<String, Object> pm = new LinkedHashMap<>();
                    pm.put("payee_id", p.payeeId());
                    pm.put("amount_minor", p.amountMinor());
                    pm.put("disputed", p.disputed());
                    pm.put("note", p.note());
                    return pm;
                }).toList());
                rightViews.add(rv);
                wouldAllocate += r.allocatedMinor();
                wouldHold += r.conflictHoldMinor();
                wouldUnclaim += r.unclaimedMinor();
            }
            lv.put("rights", rightViews);
            totalDistributable += income.totalDistributable();
            wouldUnclaim += income.budgetUnclaimedMinor();
            lineViews.add(lv);
        }
        preview.put("affected_active_lines", lines.size());
        preview.put("total_distributable_minor", totalDistributable);
        preview.put("would_allocate_minor", wouldAllocate);
        preview.put("would_conflict_hold_minor", wouldHold);
        preview.put("would_unclaimed_minor", wouldUnclaim);
        preview.put("conservation_check",
                totalDistributable == wouldAllocate + wouldHold + wouldUnclaim);
        preview.put("lines", lineViews);
        preview.put("warnings", blockers.stream().distinct().toList());
        return preview;
    }

    // ---------- 人工采用 / 拒绝 / 撤回 ----------

    @Transactional
    public Map<String, Object> adoptCandidate(long candidateId, String actor) {
        MappingCandidate candidate = reports.findCandidateById(candidateId)
                .orElseThrow(() -> new DomainException("候选不存在: " + candidateId));
        if (!"PROPOSED".equals(candidate.status())) {
            throw new DomainException("候选已处理，不能重复采用");
        }
        ReportLine line = reports.findLineById(candidate.reportLineId()).orElseThrow();
        ReportBatch batch = reports.findBatchById(line.batchId()).orElseThrow();
        if (catalog.findActiveMapping(batch.platformId(), line.platformItemCode()).isPresent()) {
            throw new DomainException("该平台编号已有有效映射，如需改派请先撤回旧映射");
        }
        long mappingId = catalog.insertProposedMapping(batch.platformId(), line.platformItemCode(),
                candidate.recordingId(), candidate.id());
        catalog.approveMapping(mappingId, actor, candidate.recordingId());
        reports.decideCandidate(candidate.id(), "ADOPTED");
        // 同编号其他候选全部拒绝
        for (ReportLine l : reports.linesOfBatch(batch.id())) {
            if (l.platformItemCode().equals(line.platformItemCode())) {
                for (MappingCandidate other : reports.candidatesOfLine(l.id())) {
                    if ("PROPOSED".equals(other.status())) {
                        reports.decideCandidate(other.id(), "REJECTED");
                    }
                }
            }
        }
        return Map.of("mapping_id", mappingId, "recording_id", candidate.recordingId(),
                "status", "APPROVED", "actor", actor);
    }

    @Transactional
    public Map<String, Object> createManualMapping(long platformId, String itemCode, long recordingId, String actor) {
        if (catalog.findActiveMapping(platformId, itemCode).isPresent()) {
            throw new DomainException("该平台编号已有有效映射，如需改派请先撤回旧映射");
        }
        long mappingId = catalog.insertProposedMapping(platformId, itemCode, recordingId, null);
        catalog.approveMapping(mappingId, actor, recordingId);
        return Map.of("mapping_id", mappingId, "recording_id", recordingId, "status", "APPROVED");
    }

    @Transactional
    public void rejectCandidate(long candidateId, String actor) {
        MappingCandidate candidate = reports.findCandidateById(candidateId)
                .orElseThrow(() -> new DomainException("候选不存在"));
        if (!"PROPOSED".equals(candidate.status())) {
            throw new DomainException("候选已处理");
        }
        reports.decideCandidate(candidateId, "REJECTED");
    }

    @Transactional
    public void revokeMapping(long mappingId, String actor, String reason) {
        catalog.revokeMapping(mappingId, actor, reason);
    }

    public List<PlatformMapping> listMappings(String status) {
        return status == null ? catalog.allMappings() : catalog.mappingsByStatus(status);
    }

    private static double round(double v) {
        return Math.round(v * 1000d) / 1000d;
    }
}
