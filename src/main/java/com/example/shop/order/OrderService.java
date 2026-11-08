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
 * Places orders (REQ-F-006): idempotency key -> stock reservation -> order row -> outbox, then the payment screen.
 * Only the DB write and the reservation are synchronous; payment preparation follows the order.created event.
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
     * @return id of the new order, or of the order an earlier request with the same key created
     * @throws ShopException OUT_OF_STOCK (nothing reserved, no order), REQUEST_IN_PROGRESS, PRODUCT_NOT_ON_SALE, ...
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

    /** A replayed key belongs to the member who used it first; anyone else gets 409 instead of that order id. */
    private long ownedOrder(long memberId, long orderId) {
        return orders.findByIdAndMemberId(orderId, memberId)
                .map(Order::getId)
                .orElseThrow(() -> new ShopException(ErrorCode.IDEMPOTENCY_KEY_REUSED));
    }

    /** Second idempotency layer: the Redis key expired or was lost, but the unique DB key finds the first order. */
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
     * One transaction: order row, reservation rows + Redis decrement (Lua), outbox row. A short product rolls the
     * order back; a failure after the Redis decrement rolls back and gives the stock back (REQ-F-006 3, 4).
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

    /** REQ-F-006: the amount always comes from the current product price, never from the client. */
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
