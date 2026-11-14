package cc.royalty.service;

import cc.royalty.common.Fingerprints;
import cc.royalty.domain.Contract;
import cc.royalty.domain.ContractShare;
import cc.royalty.domain.SettlementAllocation;
import cc.royalty.domain.SettlementLine;
import cc.royalty.domain.SoundRecording;
import cc.royalty.repo.CatalogRepository;
import cc.royalty.repo.SettlementRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * 结算批次固定合同、目录与原始明细的快照指纹。
 * 只纳入该批次实际使用的依据：结算行源指纹、被选中的合同及其份额、用到的录音。
 * 未匹配行后续被人工采用新映射，不属于本批次已用依据，不会造成误报；
 * 已采用映射的撤回/改派由 SettlementService.review 的显式检查拦截。
 */
@Component
public class SnapshotService {

    private final CatalogRepository catalog;
    private final SettlementRepository settlements;

    public SnapshotService(CatalogRepository catalog, SettlementRepository settlements) {
        this.catalog = catalog;
        this.settlements = settlements;
    }

    public String fingerprintForBatch(long batchId) {
        TreeSet<String> parts = new TreeSet<>();
        List<SettlementLine> lines = settlements.linesOfBatch(batchId);
        for (SettlementLine sl : lines) {
            parts.add("line:" + sl.id() + ":" + sl.sourceFingerprint());
            if (sl.recordingId() != null) {
                SoundRecording rec = catalog.findRecordingById(sl.recordingId()).orElse(null);
                if (rec != null) {
                    parts.add("rec:" + rec.recordingCode() + ":" + rec.status() + ":" + rec.workId());
                }
            }
            if (sl.chosenContractIds() != null && !sl.chosenContractIds().isBlank()) {
                for (String idText : sl.chosenContractIds().split(",")) {
                    long contractId = Long.parseLong(idText.trim());
                    Contract c = catalog.findContractById(contractId).orElse(null);
                    if (c == null) {
                        parts.add("contract_missing:" + contractId);
                        continue;
                    }
                    StringBuilder sb = new StringBuilder("contract:").append(c.contractCode())
                            .append(":").append(c.rightType()).append(":").append(c.status())
                            .append(":").append(c.territory()).append(":").append(c.media())
                            .append(":").append(c.validFrom()).append(":").append(c.validTo());
                    for (ContractShare s : catalog.sharesOfContract(c.id())) {
                        sb.append("|").append(s.payeeId()).append("=")
                                .append(s.shareNumerator()).append("/").append(s.shareDenominator());
                    }
                    parts.add(sb.toString());
                }
            }
        }
        // 应付与分摊也纳入指纹：任何对已算结果的篡改都会暴露
        for (SettlementLine sl : lines) {
            for (SettlementAllocation a : settlements.allocationsOfLine(sl.id())) {
                parts.add("alloc:" + sl.id() + ":" + a.payeeId() + ":" + a.allocStatus()
                        + ":" + a.shareNumerator() + "/" + a.shareDenominator() + ":" + a.amountMinor());
            }
        }
        return Fingerprints.sha256(String.join("\n", new ArrayList<>(parts)));
    }
}
