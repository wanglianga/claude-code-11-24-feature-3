package cc.royalty.web;

import cc.royalty.service.CatalogImportService;
import cc.royalty.service.PaymentService;
import cc.royalty.service.ReportImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Tag(name = "文件导入", description = "平台明细、录音目录、合同、协议、映射 CSV 导入")
@RestController
public class ImportController {

    private final CatalogImportService catalogImport;
    private final ReportImportService reportImport;
    private final PaymentService paymentService;

    public ImportController(CatalogImportService catalogImport, ReportImportService reportImport,
                            PaymentService paymentService) {
        this.catalogImport = catalogImport;
        this.reportImport = reportImport;
        this.paymentService = paymentService;
    }

    private static String text(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("文件读取失败: " + e.getMessage());
        }
    }

    @Operation(summary = "导入权利人")
    @PostMapping("/api/import/payees")
    public Object payees(@RequestPart("file") MultipartFile file) {
        return catalogImport.importPayees(text(file));
    }

    @Operation(summary = "导入词曲作品")
    @PostMapping("/api/import/works")
    public Object works(@RequestPart("file") MultipartFile file) {
        return catalogImport.importWorks(text(file));
    }

    @Operation(summary = "导入作品贡献者")
    @PostMapping("/api/import/contributors")
    public Object contributors(@RequestPart("file") MultipartFile file) {
        return catalogImport.importContributors(text(file));
    }

    @Operation(summary = "导入录音目录（明确录音标识）")
    @PostMapping("/api/import/recordings")
    public Object recordings(@RequestPart("file") MultipartFile file) {
        return catalogImport.importRecordings(text(file));
    }

    @Operation(summary = "导入平台")
    @PostMapping("/api/import/platforms")
    public Object platforms(@RequestPart("file") MultipartFile file) {
        return catalogImport.importPlatforms(text(file));
    }

    @Operation(summary = "导入合同与份额（含季度中途转权的不同有效区间）")
    @PostMapping("/api/import/contracts")
    public Object contracts(@RequestPart("file") MultipartFile file) {
        return catalogImport.importContracts(text(file));
    }

    @Operation(summary = "导入平台协议（按次单价/直接收入两种结算方式与费用条款）")
    @PostMapping("/api/import/agreements")
    public Object agreements(@RequestPart("file") MultipartFile file) {
        return catalogImport.importAgreements(text(file));
    }

    @Operation(summary = "导入预置已审核平台映射")
    @PostMapping("/api/import/mappings")
    public Object mappings(@RequestPart("file") MultipartFile file) {
        return catalogImport.importMappings(text(file));
    }

    @Operation(summary = "导入平台使用明细批次（#BATCH 头声明原始合计；坏行/重复行/撤销/修订分别处理）")
    @PostMapping("/api/import/reports")
    public Object reports(@RequestPart("file") MultipartFile file) {
        return reportImport.importReport(text(file), file.getOriginalFilename());
    }

    @Operation(summary = "导入模拟银行付款回执文件（不访问真实银行）")
    @PostMapping("/api/import/receipts")
    public Object receipts(@RequestPart("file") MultipartFile file,
                           @RequestParam(required = false) String sourceFile) {
        return paymentService.importReceipts(text(file),
                sourceFile != null ? sourceFile : file.getOriginalFilename());
    }
}
