package com.example.shop.cart;

import com.example.shop.common.ErrorCode;
import com.example.shop.common.RedisKeys;
import com.example.shop.common.ShopException;
import com.example.shop.product.ProductCatalog;
import com.example.shop.product.ProductInfo;
import com.example.shop.stock.AvailableStockReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Redis Hash RedisKeys.cart(memberId)에 담는 장바구니다 (field = productId, value = quantity). TTL은 7일이며 변경할
 * 때마다 갱신한다 (REQ-F-005). 회원 id는 항상 로그인한 principal에서 가져오므로 회원은 자기 장바구니에만 접근한다.
 */
@Service
public class CartService {

    public static final int MAX_QUANTITY_PER_PRODUCT = 10;
    public static final int MAX_PRODUCT_KINDS = 30;
    static final Duration CART_TTL = Duration.ofDays(7);

    private static final long QUANTITY_LIMIT_EXCEEDED = -1;
    private static final long CART_FULL = -2;
    private static final RedisScript<Long> ADD_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/cart-add.lua"), Long.class);
    private static final RedisScript<Long> SET_SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/cart-set.lua"), Long.class);

    private final StringRedisTemplate redis;
    private final ProductCatalog catalog;
    private final AvailableStockReader availableStock;

    public CartService(StringRedisTemplate redis, ProductCatalog catalog, AvailableStockReader availableStock) {
        this.redis = redis;
        this.catalog = catalog;
        this.availableStock = availableStock;
    }

    /**
     * 같은 상품의 기존 수량에 더한다. 두 한도 모두 Lua에서 원자적으로 검사한다.
     * <pre>
     * [IN]  memberId=1, productId=3, quantity=2
     * [Redis] EVAL cart-add.lua KEYS=[shop:cart:1] ARGV=["3","2","10","30","604800000"]
     *           = HGET shop:cart:1 3 → "1" (기존 1개)
     *             HSET shop:cart:1 3 3       (1 + 2 = 3개)
     *             PEXPIRE shop:cart:1 604800000  (TTL 7일 갱신)
     *           → 3  : 성공 (새 수량)
     *           → -1 : 상품당 10개 초과 → CART_QUANTITY_LIMIT
     *           → -2 : 상품 종류 30개 초과 → CART_ITEM_LIMIT
     * [OUT] 없음 (Redis 상태: shop:cart:1 = {"3":"3", ...})
     * </pre>
     */
    public void add(long memberId, long productId, int quantity) {
        if (!catalog.getProduct(productId).onSale()) {
            throw new ShopException(ErrorCode.PRODUCT_NOT_ON_SALE);
        }
        Long result = redis.execute(ADD_SCRIPT, List.of(RedisKeys.cart(memberId)), String.valueOf(productId),
                String.valueOf(quantity), String.valueOf(MAX_QUANTITY_PER_PRODUCT), String.valueOf(MAX_PRODUCT_KINDS),
                String.valueOf(CART_TTL.toMillis()));
        if (result == QUANTITY_LIMIT_EXCEEDED) {
            throw new ShopException(ErrorCode.CART_QUANTITY_LIMIT);
        }
        if (result == CART_FULL) {
            throw new ShopException(ErrorCode.CART_ITEM_LIMIT);
        }
    }

    /**
     * <pre>
     * [IN]  memberId=1, productId=3, quantity=5
     * [Redis] EVAL cart-set.lua KEYS=[shop:cart:1] ARGV=["3","5","604800000"]
     *           = HEXISTS shop:cart:1 3 → 1 이면 HSET shop:cart:1 3 5 + PEXPIRE → return 1
     *                                   → 0 이면 return 0 → CART_ITEM_NOT_FOUND
     * </pre>
     */
    public void changeQuantity(long memberId, long productId, int quantity) {
        Long updated = redis.execute(SET_SCRIPT, List.of(RedisKeys.cart(memberId)), String.valueOf(productId),
                String.valueOf(quantity), String.valueOf(CART_TTL.toMillis()));
        if (updated == 0) {
            throw new ShopException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    /**
     * <pre>
     * [IN]  memberId=1, productId=3
     * [Redis] HDEL shop:cart:1 3
     *         EXPIRE shop:cart:1 604800   (TTL 7일 갱신)
     * </pre>
     */
    public void remove(long memberId, long productId) {
        String key = RedisKeys.cart(memberId);
        redis.opsForHash().delete(key, String.valueOf(productId));
        redis.expire(key, CART_TTL);
    }

    /**
     * REQ-F-005 4): 장바구니에서 주문한 뒤에는 주문한 상품만 장바구니에서 뺀다.
     * <pre>
     * [IN]  memberId=1, productIds=[3, 5]
     * [Redis] HDEL shop:cart:1 3 5
     *         EXPIRE shop:cart:1 604800
     * </pre>
     */
    public void removeAll(long memberId, Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return;
        }
        String key = RedisKeys.cart(memberId);
        redis.opsForHash().delete(key, productIds.stream().map(String::valueOf).toArray());
        redis.expire(key, CART_TTL);
    }

    /**
     * <pre>
     * [IN]  memberId=1
     * [Redis] DEL shop:cart:1
     * </pre>
     */
    public void clear(long memberId) {
        redis.delete(RedisKeys.cart(memberId));
    }

    /**
     * <pre>
     * [IN]  memberId=1
     * [Redis] HGETALL shop:cart:1            → {"3":"2", "5":"1"}   (productId → 수량)
     *         MGET shop:stock:3 shop:stock:5 → ["48", "0"]           (가용 재고, AvailableStockReader)
     *         GET shop:product:3, GET shop:product:5                 (상품 캐시, ProductCatalog.getProduct)
     * [OUT] CartView[ CartLine(productId=3, name="반팔 티셔츠", price=8900, qty=2, available=48),
     *                 CartLine(productId=5, ..., qty=1, available=0) ]   (productId 순 정렬)
     * </pre>
     */
    public CartView view(long memberId) {
        Map<Object, Object> entries = redis.opsForHash().entries(RedisKeys.cart(memberId));
        List<Long> productIds = entries.keySet().stream().map(field -> Long.parseLong((String) field)).toList();
        Map<Long, Long> available = availableStock.availableOf(productIds);
        List<CartLine> lines = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            long productId = Long.parseLong((String) entry.getKey());
            ProductInfo product = catalog.getProduct(productId);
            lines.add(new CartLine(productId, product.name(), product.imageUrl(), product.price(),
                    Integer.parseInt((String) entry.getValue()), available.get(productId), product.onSale()));
        }
        lines.sort(Comparator.comparingLong(CartLine::productId));
        return new CartView(lines);
    }
}
