package cc.royalty.web;

import cc.royalty.service.EscrowService;
import cc.royalty.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 争议托管与裁决执行接口。
 * 争议声明即按 录音+权利类别+地区+时段+比例 与结算项求交冻结；
 * 裁决先预览再执行；撤回只留痕不删除历史分录。
 */
@Tag(name = "争议托管与裁决", description = "争议声明求交冻结、托管台账、裁决预览/执行/撤回、追回与托管付款")
@RestController
@RequestMapping("/api/escrow")
public class EscrowController {

    private final EscrowService escrow;
    private final PaymentService payments;

    public EscrowController(EscrowService escrow, PaymentService payments) {
        this.escrow = escrow;
        this.payments = payments;
    }

    public record DisputeRequest(String disputeNo, String recordingCode, String rightType,
                                 List<String> territories, LocalDate periodStart, LocalDate periodEnd,
                                 long ratioNumerator, long ratioDenominator, String claimantCode,
                                 String respondentCode, String legalRef, String reason, String actor) {
    }

    // ---------- 争议与冻结 ----------

    @Operation(summary = "登记争议并按声明范围与结算项求交冻结（重叠争议自动求并集，已付款生成追回）")
    @PostMapping("/disputes")
    public Object registerDispute(@RequestBody DisputeRequest req) {
        return escrow.registerDispute(new EscrowService.DisputeInput(
                req.disputeNo(), req.recordingCode(), req.rightType(), req.territories(),
                req.periodStart(), req.periodEnd(), req.ratioNumerator(), req.ratioDenominator(),
                req.claimantCode(), req.respondentCode(), req.legalRef(), req.reason(),
                req.actor() == null ? "legal" : req.actor()));
    }

    @Operation(summary = "争议列表")
    @GetMapping("/disputes")
    public Object listDisputes() {
        return escrow.listDisputes();
    }

    @Operation(summary = "争议详情：冻结单元、争议覆盖额与追回事项")
    @GetMapping("/disputes/{disputeNo}")
    public Object dispute(@PathVariable String disputeNo) {
        return escrow.disputeDetail(disputeNo);
    }

    @Operation(summary = "撤回争议：剩余冻结自动解冻，仍被其他争议覆盖的部分保留；历史分录不删除")
    @PostMapping("/disputes/{disputeNo}/withdraw")
    public Object withdrawDispute(@PathVariable String disputeNo,
                                  @RequestParam String reason,
                                  @RequestParam(defaultValue = "legal") String actor) {
        return escrow.withdrawDispute(disputeNo, reason, actor);
    }

    // ---------- 裁决 ----------

    public record ArbitrationRequest(String arbitrationNo, String disputeNo, List<String> territories,
                                     LocalDate periodStart, LocalDate periodEnd, String outcome,
                                     String beneficiaryCode, String legalBasis, String note, String actor) {
    }

    @Operation(summary = "登记法务裁决（RELEASE 释放 / TRANSFER 转付受益人 / RETAIN 继续保留）")
    @PostMapping("/arbitrations")
    public Object registerArbitration(@RequestBody ArbitrationRequest req) {
        return escrow.registerArbitration(new EscrowService.ArbitrationInput(
                req.arbitrationNo(), req.disputeNo(), req.territories(), req.periodStart(),
                req.periodEnd(), req.outcome(), req.beneficiaryCode(), req.legalBasis(),
                req.note(), req.actor() == null ? "legal" : req.actor()));
    }

    @Operation(summary = "裁决列表与执行线")
    @GetMapping("/arbitrations")
    public Object listArbitrations() {
        return escrow.listArbitrations();
    }

    @Operation(summary = "执行前预览：可释放、应转付、仍被其他争议覆盖的部分（不落库）")
    @GetMapping("/arbitrations/{arbitrationNo}/preview")
    public Object preview(@PathVariable String arbitrationNo) {
        return escrow.previewArbitration(arbitrationNo);
    }

    @Operation(summary = "执行裁决：部分失败保留进度，可重复执行且不重复付款")
    @PostMapping("/arbitrations/{arbitrationNo}/execute")
    public Object execute(@PathVariable String arbitrationNo,
                          @RequestParam(defaultValue = "legal") String actor) {
        return escrow.executeArbitration(arbitrationNo, actor);
    }

    @Operation(summary = "撤销裁决：不删除释放记录；未付暂停退回托管，已付列回收差额")
    @PostMapping("/arbitrations/{arbitrationNo}/revoke")
    public Object revokeArbitration(@PathVariable String arbitrationNo,
                                    @RequestParam String reason,
                                    @RequestParam(defaultValue = "legal") String actor) {
        return escrow.revokeArbitration(arbitrationNo, reason, actor);
    }

    // ---------- 托管账户与台账 ----------

    @Operation(summary = "托管账户守恒总览：入账 = 余额 + 待付 + 已释放")
    @GetMapping("/accounts")
    public Object accounts() {
        return escrow.listAccounts();
    }

    @Operation(summary = "托管账户台账：逐笔分录、冻结单元与待付，可逐项核对")
    @GetMapping("/accounts/{accountNo}/ledger")
    public Object ledger(@PathVariable String accountNo) {
        return escrow.accountLedger(accountNo);
    }

    @Operation(summary = "生成托管付款文件（与释放分录绑定；幂等；回执未确认仍占待付额）")
    @PostMapping("/accounts/{accountNo}/payment-runs")
    public Object escrowPaymentRun(@PathVariable String accountNo,
                                   @RequestParam(defaultValue = "legal") String actor) {
        return payments.generateEscrowPaymentRun(accountNo, actor);
    }

    // ---------- 追回 ----------

    @Operation(summary = "追回事项清单（已付出的争议款，不属托管现存资金）")
    @GetMapping("/clawbacks")
    public Object clawbacks(@RequestParam(required = false) String status) {
        return escrow.listClawbacks(status);
    }

    @Operation(summary = "登记追回款到账（入托管账，留 CLAWBACK_ADJ 分录）")
    @PostMapping("/clawbacks/{clawbackNo}/recover")
    public Object recover(@PathVariable String clawbackNo,
                          @RequestParam String recoveryRef,
                          @RequestParam(defaultValue = "legal") String actor) {
        return escrow.recoverClawback(clawbackNo, recoveryRef, actor);
    }
}
