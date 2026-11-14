package cc.royalty.web;

import cc.royalty.service.MappingService;
import cc.royalty.service.PaymentService;
import cc.royalty.service.QueryService;
import cc.royalty.service.SettlementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "结算工作流", description = "候选与映射、结算批次、复核批准、付款回执、差额单、查询清单")
@RestController
@RequestMapping("/api")
public class WorkflowController {

    private final MappingService mappingService;
    private final SettlementService settlementService;
    private final PaymentService paymentService;
    private final QueryService queryService;

    public WorkflowController(MappingService mappingService, SettlementService settlementService,
                              PaymentService paymentService, QueryService queryService) {
        this.mappingService = mappingService;
        this.settlementService = settlementService;
        this.paymentService = paymentService;
        this.queryService = queryService;
    }

    // ---------- 候选与归属 ----------

    @Operation(summary = "为报告批次生成候选（标题/作者/时长仅出候选，不自动归属）")
    @PostMapping("/batches/{batchId}/candidates")
    public Object generateCandidates(@PathVariable long batchId) {
        return mappingService.generateCandidates(batchId);
    }

    @Operation(summary = "待匹配清单（无已审映射的平台编号与候选）")
    @GetMapping("/workbench/pending-matching")
    public Object pendingMatching(@RequestParam(required = false) Long batchId) {
        return mappingService.pendingMatchings(batchId);
    }

    @Operation(summary = "采用候选前预览影响金额（不落库）")
    @GetMapping("/candidates/{candidateId}/preview")
    public Object previewCandidate(@PathVariable long candidateId) {
        return mappingService.previewAdoption(candidateId);
    }

    @Operation(summary = "人工直接指定映射的影响预览")
    @GetMapping("/mappings/preview")
    public Object previewManual(@RequestParam long platformId, @RequestParam String itemCode,
                                @RequestParam long recordingId) {
        return mappingService.previewManualMapping(platformId, itemCode, recordingId);
    }

    @Operation(summary = "采用候选并审核为正式映射")
    @PostMapping("/candidates/{candidateId}/adopt")
    public Object adopt(@PathVariable long candidateId,
                        @RequestParam(defaultValue = "settler") String actor) {
        return mappingService.adoptCandidate(candidateId, actor);
    }

    @Operation(summary = "人工建立平台编号到录音的已审映射")
    @PostMapping("/mappings")
    public Object manualMapping(@RequestParam long platformId, @RequestParam String itemCode,
                                @RequestParam long recordingId,
                                @RequestParam(defaultValue = "settler") String actor) {
        return mappingService.createManualMapping(platformId, itemCode, recordingId, actor);
    }

    @Operation(summary = "拒绝候选")
    @PostMapping("/candidates/{candidateId}/reject")
    public Object reject(@PathVariable long candidateId,
                         @RequestParam(defaultValue = "settler") String actor) {
        mappingService.rejectCandidate(candidateId, actor);
        return Map.of("candidate_id", candidateId, "status", "REJECTED");
    }

    @Operation(summary = "撤回已审映射（复核时会拦截引用它的历史结算）")
    @PostMapping("/mappings/{mappingId}/revoke")
    public Object revoke(@PathVariable long mappingId,
                         @RequestParam(defaultValue = "settler") String actor,
                         @RequestParam String reason) {
        mappingService.revokeMapping(mappingId, actor, reason);
        return Map.of("mapping_id", mappingId, "status", "REVOKED", "reason", reason);
    }

    @Operation(summary = "映射列表（可按状态过滤）")
    @GetMapping("/mappings")
    public Object mappings(@RequestParam(required = false) String status) {
        return mappingService.listMappings(status);
    }

    // ---------- 结算批次 ----------

    public record SettlementRequest(Long platformId, LocalDate periodStart, LocalDate periodEnd,
                                    java.util.List<Long> reportBatchIds, String actor) {
    }

    @Operation(summary = "创建结算批次并分段计算（同范围重复出账会被锁拒绝；reportBatchIds 为空取平台全周期）")
    @PostMapping("/settlements")
    public Object createSettlement(@RequestBody SettlementRequest req) {
        return settlementService.createAndCalculate(req.platformId(), req.periodStart(),
                req.periodEnd(), req.reportBatchIds(), req.actor() == null ? "settler" : req.actor());
    }

    @Operation(summary = "中断后续算未处理分段")
    @PostMapping("/settlements/{batchId}/resume")
    public Object resume(@PathVariable long batchId,
                         @RequestParam(defaultValue = "settler") String actor) {
        return settlementService.resume(batchId, actor);
    }

    @Operation(summary = "复核：检查采用依据撤回、快照变化与原报告行状态")
    @PostMapping("/settlements/{batchId}/review")
    public Object review(@PathVariable long batchId) {
        List<String> problems = settlementService.review(batchId);
        return Map.of("settlement_batch_id", batchId,
                "review_passed", problems.isEmpty(), "problems", problems);
    }

    @Operation(summary = "批准批次（复核不通过则拒绝），批准后才可生成付款文件")
    @PostMapping("/settlements/{batchId}/approve")
    public Object approve(@PathVariable long batchId,
                          @RequestParam(defaultValue = "settler") String actor) {
        settlementService.approve(batchId, actor);
        return Map.of("settlement_batch_id", batchId, "status", "APPROVED");
    }

    @Operation(summary = "结算批次列表")
    @GetMapping("/settlements")
    public Object listSettlements() {
        return queryService.allBatches();
    }

    @Operation(summary = "批次守恒汇总与挂账解释")
    @GetMapping("/settlements/{batchId}/summary")
    public Object summary(@PathVariable long batchId) {
        return queryService.batchSummary(batchId);
    }

    // ---------- 工作台清单 ----------

    @Operation(summary = "已计算待审清单")
    @GetMapping("/workbench/calculated-pending-review")
    public Object calculatedPendingReview() {
        return queryService.calculatedPendingReview();
    }

    @Operation(summary = "已批准待付清单")
    @GetMapping("/workbench/approved-pending-payment")
    public Object approvedPendingPayment() {
        return queryService.approvedPendingPayment();
    }

    @Operation(summary = "回执核验失败清单")
    @GetMapping("/workbench/receipt-failures")
    public Object receiptFailures(@RequestParam(required = false) Long platformId) {
        return queryService.receiptFailures(platformId);
    }

    // ---------- 付款 ----------

    @Operation(summary = "生成付款文件（重试幂等，账户与正文指纹生成时固定）")
    @PostMapping("/settlements/{batchId}/payment-runs")
    public Object paymentRun(@PathVariable long batchId,
                             @RequestParam(defaultValue = "USD") String currency,
                             @RequestParam(defaultValue = "settler") String actor) {
        return paymentService.generatePaymentRun(batchId, currency, actor);
    }

    @Operation(summary = "超时查询原付款标识（不重新发款）")
    @PostMapping("/payments/query-timeout")
    public Object queryTimeout(@RequestParam String requestId) {
        return paymentService.queryTimeout(requestId);
    }

    @Operation(summary = "付款运行明细（请求标识/账户快照/文件/回执核验状态）")
    @GetMapping("/payment-runs/{runNo}")
    public Object paymentRun(@PathVariable String runNo) {
        return queryService.paymentRun(runNo);
    }

    // ---------- 差额单 ----------

    @Operation(summary = "为已批准季度开立权利更正差额单（不直接改旧款）")
    @PostMapping("/settlements/{batchId}/adjustments")
    public Object adjustment(@PathVariable long batchId, @RequestBody AdjustmentRequest req) {
        return paymentService.createAdjustment(batchId, req.reason(),
                req.lines().stream().map(l -> new PaymentService.AdjustmentLineInput(
                        l.payeeCode(), l.rightType(), l.amountMinor(),
                        l.priorContractCode(), l.newContractCode(), l.reason())).toList());
    }

    @Operation(summary = "批准差额单并另起 ADJUSTMENT 批次出账")
    @PostMapping("/adjustments/{adjustmentNo}/approve")
    public Object approveAdjustment(@PathVariable String adjustmentNo,
                                    @RequestParam(defaultValue = "settler") String actor) {
        return paymentService.approveAdjustment(adjustmentNo, actor);
    }

    public record AdjustmentRequest(String reason, List<AdjustmentLineRequest> lines) {
    }

    public record AdjustmentLineRequest(String payeeCode, String rightType, long amountMinor,
                                        String priorContractCode, String newContractCode, String reason) {
    }

    // ---------- 应收钻取 ----------

    @Operation(summary = "权利人应收展开到合同与平台原行")
    @GetMapping("/payees/{payeeId}/receivable")
    public Object receivable(@PathVariable long payeeId) {
        return queryService.payeeReceivable(payeeId);
    }
}
