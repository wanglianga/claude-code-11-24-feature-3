package cc.royalty.common;

/** 业务规则违反：携带可展示给结算员的中文说明 */
public class DomainException extends RuntimeException {
    public DomainException(String message) {
        super(message);
    }

    public DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
