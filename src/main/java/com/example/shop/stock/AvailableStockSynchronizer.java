package com.example.shop.stock;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.RedisKeys;
import com.example.shop.common.RedisLock;
import com.example.shop.common.ShopException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기준 데이터인 DB로부터 Redis 가용 재고 카운터를 다시 만든다: DB 재고에서 아직 RESERVED인 수량을 뺀 값이다
 * (REQ-F-007 6). 기동 시와 관리자 버튼으로 실행한다 (REQ-F-013 4).
 * <p>
 * 카운터를 덮어쓰므로 RedisKeys.lock(LOCK_NAME) 락을 잡는다. 관리자 재고 조정도 같은 락을 잡는다.
 * 주문과 재고 컨슈머는 이 락을 잡지 않는다. 대신 DB를 FOR SHARE로 읽고, 그 트랜잭션이 커밋되기 전에 카운터를 쓴다.
 * 이미 Redis에서 선점한 주문은 선점 행을 삽입만 하고 커밋하지 않은 상태이므로 읽기가 그 주문을 기다리고, 나중에 시작한
 * 주문은 재동기화를 기다린다. 재고 컨슈머도 트랜잭션 안에서 Redis보다 선점 행을 먼저 바꾸므로 같은 규칙이 적용된다.
 */
@Component
public class AvailableStockSynchronizer {

    static final String LOCK_NAME = "stock-resync";
    static final Duration LOCK_TTL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(AvailableStockSynchronizer.class);

    private final StockReservationRepository reservations;
    private final StringRedisTemplate redis;
    private final RedisLock lock;
    private final TransactionTemplate transaction;

    public AvailableStockSynchronizer(StockReservationRepository reservations, StringRedisTemplate redis,
                                      RedisLock lock, TransactionTemplate transactionTemplate) {
        this.reservations = reservations;
        this.redis = redis;
        this.lock = lock;
        this.transaction = transactionTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeAtStartup() {
        try {
            synchronizeFromDatabase();
        } catch (ShopException busy) {
            log.info("stock resync skipped at startup: another instance holds the lock");
        }
    }

    /** @return 카운터를 다시 쓴 상품 수 */
    public int synchronizeFromDatabase() {
        return lock.runExclusively(LOCK_NAME, LOCK_TTL, ErrorCode.STOCK_SYNC_IN_PROGRESS, this::rewriteCounters);
    }

    private int rewriteCounters() {
        return transaction.execute(status -> writeCounters(lockAvailableStocks()));
    }

    /** 선점 행 다음에 상품 행을 잠근다. 재고 컨슈머와 같은 락 순서다 (교착 순환 없음). */
    private List<AvailableStock> lockAvailableStocks() {
        Map<Long, Long> reserved = new HashMap<>();
        for (Object[] row : reservations.lockReservedQuantities()) {
            reserved.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return reservations.lockDatabaseStocks().stream()
                .map(row -> {
                    long productId = ((Number) row[0]).longValue();
                    long stock = ((Number) row[1]).longValue();
                    return new AvailableStock(productId, stock - reserved.getOrDefault(productId, 0L));
                })
                .toList();
    }

    /**
     * epoch는 DB를 읽은 뒤, 첫 카운터를 쓰기 전에 올린다. 이 시점에 아직 대기 중인 롤백 보상은 이미 롤백이 반영된 값
     * 위에 한 번 더 적용될 것이므로 건너뛴다 ({@link AvailableStockCounter#addUnlessResynced}).
     */
    private int writeCounters(List<AvailableStock> stocks) {
        redis.opsForValue().increment(RedisKeys.stockSyncEpoch());
        for (AvailableStock stock : stocks) {
            if (stock.quantity() < 0) {
                log.warn("available stock is negative: productId={} quantity={}", stock.productId(), stock.quantity());
            }
            redis.opsForValue().set(RedisKeys.stock(stock.productId()), String.valueOf(stock.quantity()));
        }
        log.info("available stock synchronized from database: products={}", stocks.size());
        return stocks.size();
    }
}
