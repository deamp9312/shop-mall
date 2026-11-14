package com.example.shop.support;

import com.example.shop.ShopMallApplication;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;

/**
 * The application started as a separate JVM (test profile, this test run's classpath), so a test can kill it the way
 * an operating system or an operator does: SIGKILL, no shutdown hooks, no graceful stop of the Kafka listeners.
 * Output goes to target/app-process/{name}.log.
 *
 * 별도 JVM으로 실행한 애플리케이션 (test 프로필, 이번 테스트 실행의 클래스패스). 그래서 테스트가 운영체제나 운영자처럼
 * SIGKILL로, 종료 훅도 Kafka 리스너의 정상 종료도 없이 죽일 수 있다.
 * 출력은 target/app-process/{name}.log로 간다.
 */
public final class ApplicationProcess {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(120);
    private static final Path LOG_DIRECTORY = Path.of("target", "app-process");

    private final String name;
    private final Process process;
    private final int port;

    private ApplicationProcess(String name, Process process, int port) {
        this.name = name;
        this.process = process;
        this.port = port;
    }

    /**
     * Starts the application with the given properties and waits until /actuator/health answers UP.
     *
     * 주어진 프로퍼티로 애플리케이션을 시작하고 /actuator/health가 UP을 응답할 때까지 기다린다.
     */
    public static ApplicationProcess start(String name, Map<String, String> properties) throws IOException {
        int port = freePort();
        List<String> command = new ArrayList<>(List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                ShopMallApplication.class.getName(), "--spring.profiles.active=test", "--server.port=" + port));
        properties.forEach((key, value) -> command.add("--" + key + "=" + value));
        Files.createDirectories(LOG_DIRECTORY);
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(LOG_DIRECTORY.resolve(name + ".log").toFile())
                .start();
        ApplicationProcess application = new ApplicationProcess(name, process, port);
        application.awaitHealthy();
        return application;
    }

    /**
     * SIGKILL and wait for the process to be gone.
     *
     * SIGKILL을 보내고 프로세스가 사라질 때까지 기다린다.
     */
    public void kill() throws InterruptedException {
        process.destroyForcibly();
        process.waitFor();
    }

    public Path log() {
        return LOG_DIRECTORY.resolve(name + ".log");
    }

    private void awaitHealthy() {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest health = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5)).build();
        await().atMost(STARTUP_TIMEOUT).pollInterval(Duration.ofMillis(500))
                .ignoreExceptionsInstanceOf(IOException.class).until(() -> {
            if (!process.isAlive()) {
                throw new IllegalStateException(name + " exited during startup, see " + log());
            }
            return client.send(health, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        });
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
