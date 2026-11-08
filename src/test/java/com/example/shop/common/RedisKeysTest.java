package com.example.shop.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeysTest {

    @Test
    void buildsEveryKeyOfTheContract() {
        assertThat(RedisKeys.SESSION_NAMESPACE).isEqualTo("shop:session");
        assertThat(RedisKeys.product(7L)).isEqualTo("shop:product:7");
        assertThat(RedisKeys.cart(3L)).isEqualTo("shop:cart:3");
        assertThat(RedisKeys.stock(12L)).isEqualTo("shop:stock:12");
        assertThat(RedisKeys.idempotency(IdempotencyScope.ORDER, "abc")).isEqualTo("shop:idem:order:abc");
        assertThat(RedisKeys.idempotency(IdempotencyScope.PAYMENT, "42")).isEqualTo("shop:idem:payment:42");
        assertThat(RedisKeys.lock("order-timeout")).isEqualTo("shop:lock:order-timeout");
        assertThat(RedisKeys.eventDone("shop-order", "e-1")).isEqualTo("shop:event:done:shop-order:e-1");
        assertThat(RedisKeys.loginFailure("User1@Shop.Local")).isEqualTo("shop:login:fail:user1@shop.local");
    }

    @Test
    void productCachePrefixPlusIdIsTheProductKey() {
        assertThat(RedisKeys.PRODUCT_CACHE_PREFIX + 7).isEqualTo(RedisKeys.product(7L));
    }

    @Test
    void stockScanPatternMatchesStockKeysOnly() {
        assertThat(RedisKeys.STOCK_SCAN_PATTERN).isEqualTo("shop:stock:*");
        assertThat(RedisKeys.stockSyncEpoch()).isEqualTo("shop:stock-sync:epoch").doesNotStartWith("shop:stock:");
    }
}
