package cc.royalty.domain;

import java.time.LocalDate;

public record Payee(Long id, String payeeCode, String payeeName, String payeeType,
                    String bankAccount, String accountCurrency, String status) {
}
