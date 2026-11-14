package com.example.shop;

import com.example.shop.common.RedisKeys;
import com.example.shop.product.Product;
import com.example.shop.product.ProductRepository;
import com.example.shop.support.SeededIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-DA-003: with the seed location enabled (as the local profile does), a fresh database gets 20 products and
 * startup writes 20 Redis stock keys equal to the DB stock. The seeded context is isolated from tests that run
 * without seed data (see SeededIntegrationTestSupport).
 *
 * REQ-DA-003: 시드 위치를 켜면(local 프로필처럼) 새 DB에 상품 20개가 들어가고, 시작할 때 DB 재고와 같은 Redis 재고 키
 * 20개가 써진다. 시드가 있는 컨텍스트는 시드 없이 도는 테스트와 분리된다 (SeededIntegrationTestSupport 참고).
 */
class SeedDataIntegrationTest extends SeededIntegrationTestSupport {

    @Autowired
    private ProductRepository products;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seedHasCategoriesProductsAndAccounts() {
        assertThat(jdbc.queryForObject("select count(*) from categories", Integer.class)).isEqualTo(4);
        assertThat(products.count()).isEqualTo(20);
        assertThat(products.findAll()).extracting(Product::getStock)
                .contains(0, 10)
                .allSatisfy(stock -> assertThat(stock).isBetween(0, 100));
        assertThat(jdbc.queryForList("select role from members order by id", String.class))
                .containsExactly("USER", "USER", "ADMIN");
        assertThat(jdbc.queryForList("select password_hash from members", String.class))
                .allSatisfy(hash -> assertThat(hash).startsWith("$2"));
    }

    @Test
    void startupWritesOneStockKeyPerProductEqualToDatabaseStock() {
        Set<String> stockKeys = scanStockKeys();

        assertThat(stockKeys).hasSize(20);
        List<Product> all = products.findAll();
        for (Product product : all) {
            assertThat(redis.opsForValue().get(RedisKeys.stock(product.getId())))
                    .as("product %d", product.getId())
                    .isEqualTo(String.valueOf(product.getStock()));
        }
    }

    private Set<String> scanStockKeys() {
        Set<String> keys = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(RedisKeys.STOCK_SCAN_PATTERN)
                .count(100).build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }
}
