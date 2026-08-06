package ai.dashaun.sekisho.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Obtains and caches a Sekisho access token via the OAuth2 client-credentials grant against Inkan.
 *
 * <p>This is the whole "how a Spring AI app authenticates to Sekisho" story: the app exchanges its
 * client id and secret for a short-lived JWT, then presents that JWT as the bearer token on every
 * OpenAI-compatible call. The token is cached and refreshed synchronously just before expiry, because
 * it is injected from an interceptor running on the HTTP client's own call thread.
 */
public class SekishoTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(SekishoTokenProvider.class);
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    private final SekishoAuthProperties properties;
    private final HttpClient httpClient;
    private final Clock clock;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final Object refreshLock = new Object();

    private volatile String cachedToken;
    private volatile Instant expiresAt = Instant.EPOCH;

    public SekishoTokenProvider(SekishoAuthProperties properties) {
        this(properties, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Clock.systemUTC());
    }

    SekishoTokenProvider(SekishoAuthProperties properties, HttpClient httpClient, Clock clock) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    /** A current bearer token value, refreshing synchronously if the cached one is near expiry. */
    public String token() {
        if (clock.instant().isBefore(expiresAt)) {
            return cachedToken;
        }
        synchronized (refreshLock) {
            if (clock.instant().isBefore(expiresAt)) {
                return cachedToken;
            }
            refresh();
            return cachedToken;
        }
    }

    private void refresh() {
        String form = "grant_type=client_credentials&scope="
                + URLEncoder.encode(properties.scope(), StandardCharsets.UTF_8);
        String basic = Base64.getEncoder().encodeToString(
                (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.tokenUri()))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Inkan token endpoint returned HTTP " + response.statusCode());
            }
            JsonNode node = mapper.readTree(response.body());
            this.cachedToken = node.get("access_token").asString();
            long expiresIn = node.has("expires_in") ? node.get("expires_in").asLong() : 900L;
            this.expiresAt = clock.instant().plusSeconds(Math.max(30, expiresIn - EXPIRY_SKEW.getSeconds()));
            log.debug("sekisho_token_refreshed client={} expires_in_s={}", properties.clientId(), expiresIn);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted obtaining Sekisho token", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to obtain Sekisho token from Inkan", failure);
        }
    }
}
