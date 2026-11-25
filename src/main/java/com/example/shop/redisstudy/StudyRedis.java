package com.example.shop.redisstudy;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Redis 학습 단계들이 공유하는 도우미. 쇼핑몰 애플리케이션과는 무관하며 Spring 빈도 아니다.
 * <p>
 * 접속 정보: 환경변수 REDIS_HOST / REDIS_PORT / REDIS_PASSWORD → 없으면 프로젝트 루트의 .env → 없으면 localhost:6379, 비밀번호 없음.
 * (IDE에서 main()을 실행하면 작업 디렉터리가 프로젝트 루트라 .env를 그대로 읽는다)
 * <p>
 * 모든 학습 키는 "study:" 로 시작한다. 쇼핑몰 키("shop:")와 섞이지 않게 하기 위해서다.
 */
public final class StudyRedis {

    public static final String HOST = setting("REDIS_HOST", "localhost");
    public static final int PORT = Integer.parseInt(setting("REDIS_PORT", "6379"));
    private static final String PASSWORD = setting("REDIS_PASSWORD", "");

    static {
        // Spring Boot 밖에서 실행하면 logback 기본값(DEBUG) 때문에 Lettuce 로그가 쏟아진다. WARN 이상만 보이게 한다.
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.WARN);
    }

    private StudyRedis() {
    }

    /** Lettuce 클라이언트. 다 쓰면 shutdown() 할 것 (try-with-resources 대신 finally). */
    public static RedisClient newClient() {
        RedisURI.Builder uri = RedisURI.builder().withHost(HOST).withPort(PORT);
        if (!PASSWORD.isEmpty()) {
            uri.withPassword(PASSWORD.toCharArray());
        }
        return RedisClient.create(uri.build());
    }

    /** STEP 7, 8 (Spring Data Redis)용 연결 팩토리. 쇼핑몰에서는 Spring Boot가 application.yml로 만들어 준다. */
    public static LettuceConnectionFactory newConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(HOST, PORT);
        if (!PASSWORD.isEmpty()) {
            config.setPassword(PASSWORD);
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet(); // Spring 컨테이너 밖에서 쓰므로 초기화를 직접 호출
        return factory;
    }

    /**
     * pattern에 맞는 키를 모두 지운다. 각 단계가 시작할 때 이전 실행의 흔적을 지우려고 쓴다.
     * <pre>
     * [Redis] SCAN 0 MATCH study:step1:* COUNT 100 → 커서 + 키 목록, 커서가 0이 될 때까지 반복
     *         DEL study:step1:greeting study:step1:temp ...
     * </pre>
     * KEYS 명령은 키가 많으면 Redis 전체를 멈추게 하므로 운영에서 쓰면 안 된다. 항상 SCAN을 쓴다.
     */
    public static void deleteKeys(RedisCommands<String, String> redis, String pattern) {
        ScanCursor cursor = ScanCursor.INITIAL;
        do {
            KeyScanCursor<String> page = redis.scan(cursor, ScanArgs.Builder.matches(pattern).limit(100));
            List<String> keys = page.getKeys();
            if (!keys.isEmpty()) {
                redis.del(keys.toArray(String[]::new));
            }
            cursor = page;
        } while (!cursor.isFinished());
    }

    private static String setting(String name, String defaultValue) {
        String fromEnv = System.getenv(name);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        Path dotEnv = Path.of(".env");
        if (Files.exists(dotEnv)) {
            try {
                for (String line : Files.readAllLines(dotEnv)) {
                    if (line.startsWith(name + "=")) {
                        String value = line.substring(name.length() + 1).trim();
                        return value.isEmpty() ? defaultValue : value;
                    }
                }
            } catch (IOException ignored) {
                // .env를 못 읽으면 기본값 사용
            }
        }
        return defaultValue;
    }
}
