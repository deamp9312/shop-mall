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
 * Cart in the Redis Hash RedisKeys.cart(memberId) (field = productId, value = quantity), TTL 7 days renewed on every
 * change (REQ-F-005). The member id always comes from the logged-in principal, so a member only reaches their own cart.
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

    /** Adds to the existing quantity of the same product; checked against both limits atomically in Lua. */
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

    /** REQ-F-005 4): after an order from the cart, only the ordered products leave the cart. */
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
