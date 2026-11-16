package com.example.shop.order;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.IdempotencyKeys;
import com.example.shop.common.IdempotencyScope;
import com.example.shop.common.MdcKeys;
import com.example.shop.common.ShopException;
import com.example.shop.event.EventOutbox;
import com.example.shop.event.OrderCreatedEvent;
import com.example.shop.product.ProductCatalog;
import com.example.shop.product.ProductInfo;
import com.example.shop.stock.OutOfStockException;
import com.example.shop.stock.StockReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 주문을 접수한다 (REQ-F-006): 멱등 키 -> 재고 예약 -> 주문 행 -> outbox 순으로 처리한 뒤 결제 화면으로 넘긴다.
 * DB 쓰기와 예약만 동기로 처리하며, 결제 준비는 order.created 이벤트를 받아 이어서 진행한다.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final ProductCatalog catalog;
    private final StockReservationService stock;
    private final EventOutbox outbox;
    private final IdempotencyKeys idempotencyKeys;
    private final TransactionTemplate transaction;
    private final Duration paymentTimeout;

    public OrderService(OrderRepository orders, ProductCatalog catalog, StockReservationService stock,
                        EventOutbox outbox, IdempotencyKeys idempotencyKeys, TransactionTemplate transaction,
                        @Value("${shop.order.payment-timeout}") Duration paymentTimeout) {
        this.orders = orders;
        this.catalog = catalog;
        this.stock = stock;
        this.outbox = outbox;
        this.idempotencyKeys = idempotencyKeys;
        this.transaction = transaction;
        this.paymentTimeout = paymentTimeout;
    }

    /**
     * @return 새 주문의 id, 또는 같은 키로 앞서 들어온 요청이 생성한 주문의 id
     * @throws ShopException OUT_OF_STOCK (예약된 것도, 생성된 주문도 없음), REQUEST_IN_PROGRESS, PRODUCT_NOT_ON_SALE, ...
     */
    public long place(long memberId, String idempotencyKey, List<OrderRequestLine> items, ShippingInfo shipping) {
        Optional<String> earlierResult = idempotencyKeys.claim(IdempotencyScope.ORDER, idempotencyKey);
        if (earlierResult.isPresent()) {
            return ownedOrder(memberId, Long.parseLong(earlierResult.get()));
        }
        try {
            long orderId = createOrFindExisting(memberId, idempotencyKey, items, shipping);
            idempotencyKeys.complete(IdempotencyScope.ORDER, idempotencyKey, String.valueOf(orderId));
            return orderId;
        } catch (RuntimeException failure) {
            idempotencyKeys.abandon(IdempotencyScope.ORDER, idempotencyKey);
            throw failure;
        } finally {
            MDC.remove(MdcKeys.ORDER_ID);
        }
    }

    public Order findOwned(long memberId, long orderId) {
        return orders.findByIdAndMemberId(orderId, memberId)
                .orElseThrow(() -> new ShopException(ErrorCode.ORDER_NOT_FOUND));
    }

    /** 재사용된 키는 처음 사용한 회원의 것이다. 다른 회원은 해당 주문 id 대신 409를 받는다. */
    private long ownedOrder(long memberId, long orderId) {
        return orders.findByIdAndMemberId(orderId, memberId)
                .map(Order::getId)
                .orElseThrow(() -> new ShopException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    /** 두 번째 멱등 계층: Redis 키가 만료되거나 유실되어도 DB 유니크 키로 최초 주문을 찾는다. */
    private long createOrFindExisting(long memberId, String idempotencyKey, List<OrderRequestLine> items,
                                      ShippingInfo shipping) {
        List<OrderLine> lines = priceOnServer(items);
        try {
            return create(memberId, idempotencyKey, lines, shipping);
        } catch (DataIntegrityViolationException duplicate) {
            return orders.findByMemberIdAndIdempotencyKey(memberId, idempotencyKey)
                    .map(Order::getId)
                    .orElseThrow(() -> duplicate);
        }
    }

    /**
     * 하나의 트랜잭션: 주문 행, 예약 행 + Redis 차감(Lua), outbox 행. 재고가 부족한 상품이 있으면 주문을 롤백하고,
     * Redis 차감 이후에 실패하면 롤백하면서 재고를 되돌린다 (REQ-F-006 3, 4).
     */
    private long create(long memberId, String idempotencyKey, List<OrderLine> lines, ShippingInfo shipping) {
        return transaction.execute(status -> {
            Order order = orders.save(Order.place(memberId, idempotencyKey, shipping, lines,
                    LocalDateTime.now().plus(paymentTimeout)));
            MDC.put(MdcKeys.ORDER_ID, String.valueOf(order.getId()));
            reserveStock(order, lines);
            outbox.record(createdEvent(order));
            log.info("order placed: totalAmount={} lines={}", order.getTotalAmount(), lines.size());
            return order.getId();
        });
    }

    private void reserveStock(Order order, List<OrderLine> lines) {
        Map<Long, Integer> quantities = lines.stream()
                .collect(Collectors.toMap(OrderLine::productId, OrderLine::quantity));
        try {
            stock.reserve(order.getId(), quantities);
        } catch (OutOfStockException shortage) {
            throw new ShopException(ErrorCode.OUT_OF_STOCK,
                    ErrorCode.OUT_OF_STOCK.defaultMessage() + " " + namesOf(shortage.getProductIds(), lines));
        }
    }

    /** REQ-F-006: 금액은 클라이언트 값이 아니라 항상 현재 상품 가격으로 정한다. */
    private List<OrderLine> priceOnServer(List<OrderRequestLine> items) {
        Map<Long, OrderRequestLine> byProduct = new LinkedHashMap<>();
        for (OrderRequestLine item : items) {
            if (byProduct.put(item.productId(), item) != null) {
                throw new ShopException(ErrorCode.INVALID_INPUT);
            }
        }
        return byProduct.values().stream().map(this::toOrderLine).toList();
    }

    private OrderLine toOrderLine(OrderRequestLine item) {
        ProductInfo product = catalog.getProduct(item.productId());
        if (!product.onSale()) {
            throw new ShopException(ErrorCode.PRODUCT_NOT_ON_SALE);
        }
        return new OrderLine(product.id(), product.name(), product.price(), item.quantity());
    }

    private static String namesOf(List<Long> productIds, List<OrderLine> lines) {
        return lines.stream()
                .filter(line -> productIds.contains(line.productId()))
                .map(OrderLine::productName)
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static OrderCreatedEvent createdEvent(Order order) {
        List<OrderCreatedEvent.Item> items = order.getItems().stream()
                .map(item -> new OrderCreatedEvent.Item(item.getProductId(), item.getQuantity(), item.getUnitPrice()))
                .toList();
        return new OrderCreatedEvent(order.getId(), order.getMemberId(), items, order.getTotalAmount(),
                order.getPaymentExpiresAt());
    }
}
