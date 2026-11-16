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

    /** 같은 상품의 기존 수량에 더한다. 두 한도 모두 Lua에서 원자적으로 검사한다. */
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

    public void changeQuantity(long memberId, long productId, int quantity) {
        Long updated = redis.execute(SET_SCRIPT, List.of(RedisKeys.cart(memberId)), String.valueOf(productId),
                String.valueOf(quantity), String.valueOf(CART_TTL.toMillis()));
        if (updated == 0) {
            throw new ShopException(ErrorCode.CART_ITEM_NOT_FOUND);
        }
    }

    public void remove(long memberId, long productId) {
        String key = RedisKeys.cart(memberId);
        redis.opsForHash().delete(key, String.valueOf(productId));
        redis.expire(key, CART_TTL);
    }

    /** REQ-F-005 4): 장바구니에서 주문한 뒤에는 주문한 상품만 장바구니에서 뺀다. */
    public void removeAll(long memberId, Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return;
        }
        String key = RedisKeys.cart(memberId);
        redis.opsForHash().delete(key, productIds.stream().map(String::valueOf).toArray());
        redis.expire(key, CART_TTL);
    }

    public void clear(long memberId) {
        redis.delete(RedisKeys.cart(memberId));
    }

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
