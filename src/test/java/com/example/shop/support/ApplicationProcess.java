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

    /** Starts the application with the given properties and waits until /actuator/health answers UP. */
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

    /** SIGKILL and wait for the process to be gone. */
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
