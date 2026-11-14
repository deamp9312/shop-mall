package com.example.shop.support;

import com.example.shop.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Products created by a test on the empty schema, with the Redis available stock set to the DB stock. Each test class
 * uses its own id range and calls {@link #deleteAll()} afterwards.
 *
 * 빈 스키마에서 테스트가 만든 상품. Redis 가용 재고를 DB 재고와 같게 맞춘다. 테스트 클래스마다 자기 id 범위를 쓰고
 * 끝나면 {@link #deleteAll()}을 호출한다.
 */
public final class TestProducts {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final long categoryId;

    public TestProducts(JdbcTemplate jdbc, StringRedisTemplate redis, long categoryId) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.categoryId = categoryId;
    }

    public void create(long productId, long price, int stock) {
        jdbc.update("insert ignore into categories (id, name) values (?, ?)", categoryId, "test-" + categoryId);
        jdbc.update("insert into products (id, category_id, name, description, price, stock, image_url, created_at,"
                + " updated_at) values (?, ?, ?, 'd', ?, ?, '/x.svg', now(6), now(6))",
                productId, categoryId, "product-" + productId, price, stock);
        redis.opsForValue().set(RedisKeys.stock(productId), String.valueOf(stock));
        redis.delete(RedisKeys.product(productId));
    }

    public String available(long productId) {
        return redis.opsForValue().get(RedisKeys.stock(productId));
    }

    public int databaseStock(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    /**
     * Also removes the orders, reservations and outbox rows of these products' test orders.
     *
     * 이 상품들로 만든 테스트 주문의 주문, 선점, 아웃박스 행도 지운다.
     */
    public void deleteAll() {
        jdbc.update("delete from outbox_events where message_key in (select cast(order_id as char)"
                + " from stock_reservations r join products p on p.id = r.product_id where p.category_id = ?)",
                categoryId);
        jdbc.update("delete from stock_reservations where product_id in (select id from products"
                + " where category_id = ?)", categoryId);
        for (Long productId : jdbc.queryForList("select id from products where category_id = ?", Long.class,
                categoryId)) {
            redis.delete(RedisKeys.stock(productId));
            redis.delete(RedisKeys.product(productId));
        }
        jdbc.update("delete from products where category_id = ?", categoryId);
        jdbc.update("delete from categories where id = ?", categoryId);
    }
}
