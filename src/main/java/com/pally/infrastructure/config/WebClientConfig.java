package com.pally.infrastructure.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Bounded WebClient used by every Claude call.
 *
 * <p>Without these timeouts a slow or hung Anthropic response holds a
 * server worker thread indefinitely; enough simultaneous hangs exhaust
 * the pool and the whole app stops accepting requests. The audit's
 * highest-impact reliability fix.
 *
 * <p>Streaming requests get a longer read window than the unary
 * response-timeout because each chunk is its own read event; overall
 * conversation length is bounded by the per-Flux .timeout() in
 * ClaudeApiClient itself.
 */
@Configuration
public class WebClientConfig {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(60);
    private static final int READ_TIMEOUT_S = 90;
    private static final int WRITE_TIMEOUT_S = 30;
    /// 4MB — wiki compile responses + photo OCR payloads need headroom
    /// over the WebFlux default of 256KB.
    private static final int MAX_IN_MEMORY_BYTES = 4 * 1024 * 1024;

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .responseTimeout(RESPONSE_TIMEOUT)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(
                                READ_TIMEOUT_S, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(
                                WRITE_TIMEOUT_S, TimeUnit.SECONDS)));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
