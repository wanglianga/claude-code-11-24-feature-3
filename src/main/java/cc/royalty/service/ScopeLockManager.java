package cc.royalty.service;

import cc.royalty.repo.SettlementRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算范围锁的获取必须在独立事务中完成：
 * 唯一约束冲突会中止当前 PostgreSQL 事务，REQUIRES_NEW 保证冲突只回滚内层尝试，
 * 不污染外层结算事务，也不会把 25P02 异常带到后续语句。
 */
@Component
public class ScopeLockManager {

    private final SettlementRepository settlements;

    public ScopeLockManager(SettlementRepository settlements) {
        this.settlements = settlements;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(String scopeKey, long settlementBatchId, String actor) {
        return settlements.tryAcquireScopeLock(scopeKey, settlementBatchId, actor);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bindBatch(String scopeKey, long batchId) {
        settlements.updateScopeLockBatch(scopeKey, batchId);
    }
}
