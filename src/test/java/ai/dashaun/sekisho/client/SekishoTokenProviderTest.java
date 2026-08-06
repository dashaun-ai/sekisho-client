package ai.dashaun.sekisho.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the token provider against a real (in-JVM) token endpoint so the actual HTTP path,
 * Basic-auth encoding, and JSON parsing are covered — not a mock of them.
 */
class SekishoTokenProviderTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private volatile String body = "{\"access_token\":\"tok-1\",\"expires_in\":900}";
    private volatile int status = 200;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            calls.incrementAndGet();
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** A mutable clock so token expiry is exercised deterministically, without sleeping. */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-26T00:00:00Z"));

    private SekishoAuthProperties props() {
        return new SekishoAuthProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/token",
                "kansei", "s3cret", "models.invoke");
    }

    private SekishoTokenProvider provider() {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        return new SekishoTokenProvider(props(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), clock);
    }

    @Test
    void fetchesTokenWithHttpBasicClientCredentials() {
        String token = provider().token();

        assertThat(token).isEqualTo("tok-1");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("kansei:s3cret".getBytes(StandardCharsets.UTF_8));
        assertThat(authHeader.get()).isEqualTo(expected);
    }

    @Test
    void cachesTokenUntilNearExpiryInsteadOfRefetching() {
        SekishoTokenProvider provider = provider();

        provider.token();
        provider.token();
        provider.token();

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void refreshesOnceTheCachedTokenHasExpired() {
        // 900s token minus 60s skew => valid for 840s. Advancing past that forces exactly one refresh.
        SekishoTokenProvider provider = provider();

        assertThat(provider.token()).isEqualTo("tok-1");
        assertThat(provider.token()).isEqualTo("tok-1");
        assertThat(calls.get()).isEqualTo(1);

        body = "{\"access_token\":\"tok-2\",\"expires_in\":900}";
        now.updateAndGet(t -> t.plus(Duration.ofSeconds(841)));

        assertThat(provider.token()).isEqualTo("tok-2");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void surfacesANonSuccessResponseAsAClearFailure() {
        status = 401;
        body = "nope";

        assertThatThrownBy(() -> provider().token())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to obtain Sekisho token");
    }
}
