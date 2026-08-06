package ai.dashaun.sekisho.client;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Contributes the Sekisho client-credentials wiring: a {@link SekishoTokenProvider}, and — only when
 * Spring AI's OpenAI transport is on the classpath — an OkHttp interceptor that replaces the
 * {@code Authorization} header on every model call with a live bearer token. The configured
 * {@code spring.ai.openai.api-key} is therefore just a placeholder.
 */
@AutoConfiguration
@EnableConfigurationProperties(SekishoAuthProperties.class)
public class SekishoClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SekishoTokenProvider sekishoTokenProvider(SekishoAuthProperties properties) {
        return new SekishoTokenProvider(properties);
    }

    /**
     * The bearer interceptor, and the request timeout that goes with talking to a self-hosted model.
     *
     * <p>The default is 5 minutes because the first call after a model is evicted is not a normal
     * call: Ollama has to load the weights before it answers a single token. Measured on this
     * platform, a cold 117B model took <strong>1m54s</strong> to answer "Say WORKING" and 0.5s once
     * resident. The SDK's stock timeout is well under that, so the very first question anyone asked
     * after an idle period failed — and failed as a dead socket, which is the worst shape of failure
     * for a streamed reply: the browser receives zero bytes and shows nothing at all.
     *
     * <p>Raise it further with {@code sekisho.timeout} if your model is larger or your disk slower.
     * This bounds a single model call, not the SSE connection, which has no timeout of its own.
     */
    @Bean
    @ConditionalOnClass(OpenAiHttpClientBuilderCustomizer.class)
    @ConditionalOnMissingBean
    OpenAiHttpClientBuilderCustomizer sekishoBearerTokenCustomizer(
            SekishoTokenProvider tokens,
            @Value("${sekisho.timeout:5m}") Duration timeout) {
        return builder -> builder
                .timeout(timeout)
                .interceptor(chain -> chain.proceed(chain.request().newBuilder()
                        .header("Authorization", "Bearer " + tokens.token())
                        .build()));
    }
}
