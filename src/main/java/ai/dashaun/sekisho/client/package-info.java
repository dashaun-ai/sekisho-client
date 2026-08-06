/**
 * The client side of the Sekisho barrier: how a Spring AI application obtains and presents a
 * client-credentials JWT from Inkan on every model call. Extracted so Kansei and Tegata (and any
 * future machine client) share one token provider and one bearer-wiring path instead of copies.
 *
 * <p>Add the jar, set {@code sekisho.client.*}, and the auto-configuration contributes a
 * {@link ai.dashaun.sekisho.client.SekishoTokenProvider} and — when Spring AI's OpenAI transport is
 * present — an interceptor that stamps {@code Authorization: Bearer <token>} on each request.
 */
@org.jspecify.annotations.NullMarked
package ai.dashaun.sekisho.client;
