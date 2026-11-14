package cc.royalty.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PlatformAgreement(Long id, Long platformId, String rightType, String territory,
                                String media, String settlementMethod, BigDecimal rateMinor,
                                String currency, String feeBasis, long feeNumerator, long feeDenominator,
                                long budgetNumerator, long budgetDenominator,
                                LocalDate validFrom, LocalDate validTo, String status) {
}
