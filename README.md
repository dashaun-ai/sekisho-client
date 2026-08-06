# sekisho-client

How a Spring AI application authenticates to a [Sekisho](https://github.com/dashaun-ai) model gateway.

Spring AI's OpenAI transport wants a static `api-key`. A gateway that fronts several model backends
wants a short-lived, per-caller token instead — so it can tell who is asking, what they may use, and
cut them off without redeploying anyone. This library reconciles the two: add the jar, set four
properties, and every outbound model call carries a fresh OAuth2 client-credentials JWT instead of
the placeholder key.

There is no code to write. It is one auto-configuration, and the seam it hooks is Spring AI's own
`OpenAiHttpClientBuilderCustomizer`.

## Use it

```xml
<dependency>
    <groupId>ai.dashaun</groupId>
    <artifactId>sekisho-client</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:18080/v1   # the gateway, not the model provider
      api-key: placeholder-jwt-injected     # replaced per request; never a real key

sekisho:
  client:
    token-uri: http://localhost:9000/oauth2/token
    client-id: your-client-id
    client-secret: ${YOUR_SECRET:}          # from the environment, never the repository
    scope: models.invoke
  timeout: 5m
```

That is the whole integration. `SekishoTokenProvider` fetches and caches the token, refreshing it
before expiry; `SekishoClientAutoConfiguration` installs the interceptor that stamps it on each call.

## Three things worth knowing

**A blank secret stops the application.** `SekishoAuthProperties` rejects it in its compact
constructor rather than letting the app boot and fail later against a real gateway. A missing
credential is a configuration error, and configuration errors should be loud and early.

**The timeout is minutes, not seconds.** A self-hosted model that has been evicted from memory must
load its weights before it answers a single token. Measured on the platform this was built for, a
cold 117B model took **1m54s** to answer one word and 0.5s once resident. The SDK's stock timeout is
well under that, so without `sekisho.timeout` the first request after any idle period fails — and
fails as a dead socket, which is the worst shape of failure for a streamed reply: the browser
receives zero bytes and shows nothing at all.

**Spring AI is an optional dependency.** The bearer customizer is `@ConditionalOnClass`, so an
application that only wants the token provider does not drag Spring AI in.

## Build

Requires **JDK 25** (a Maven Enforcer rule fails fast otherwise; `sdk env` reads `.sdkmanrc`).

```bash
sdk env
./mvnw verify        # 4 tests, against a stub token endpoint — no network
./mvnw install       # publish to ~/.m2 so a consumer can resolve it
```

Not on Maven Central yet, so consumers build it locally first. See
[tegata](https://github.com/dashaun-ai/tegata) for a working consumer.

## Configuration

| property | default | meaning |
|---|---|---|
| `sekisho.client.token-uri` | *(required)* | the authorization server's token endpoint |
| `sekisho.client.client-id` | *(required)* | this application's registered client id |
| `sekisho.client.client-secret` | *(required, rejects blank)* | supplied from the environment |
| `sekisho.client.scope` | `models.invoke` | the scope requested |
| `sekisho.timeout` | `5m` | how long one model call may take |

`.envrc.example` shows these as environment variables.
