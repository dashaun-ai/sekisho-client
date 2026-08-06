package ai.dashaun.sekisho.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Client-credentials configuration for obtaining Sekisho access tokens from Inkan.
 *
 * @param tokenUri     Inkan's OAuth2 token endpoint (e.g. {@code http://localhost:9000/oauth2/token})
 * @param clientId     this application's registered client id (e.g. {@code kansei} or {@code tegata})
 * @param clientSecret the client secret, supplied from the environment, never the repository
 * @param scope        the requested scope; defaults to {@code models.invoke}
 */
@ConfigurationProperties("sekisho.client")
public record SekishoAuthProperties(String tokenUri, String clientId, String clientSecret, String scope) {

    public SekishoAuthProperties {
        if (tokenUri == null || tokenUri.isBlank()) {
            throw new IllegalArgumentException("sekisho.client.token-uri is required");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("sekisho.client.client-id is required");
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("sekisho.client.client-secret is required");
        }
        scope = (scope == null || scope.isBlank()) ? "models.invoke" : scope;
    }
}
