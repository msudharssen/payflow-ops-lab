package com.payflow.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * Calls the fraud-check downstream (WireMock in the lab).
 * POST /score -> {"score": 0.42}
 */
@Component
public class FraudCheckClient {

    private static final Logger log = LoggerFactory.getLogger(FraudCheckClient.class);

    private final RestClient restClient;
    private final Timer latencyTimer;

    public FraudCheckClient(@Value("${payflow.fraud-check.url}") String baseUrl,
                            MeterRegistry registry) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(1000));
        factory.setReadTimeout(Duration.ofMillis(2500));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
        this.latencyTimer = Timer.builder("fraudcheck_latency")
                .description("Latency of calls to the fraud-check downstream")
                .publishPercentileHistogram()
                .register(registry);
    }

    @SuppressWarnings("unchecked")
    public BigDecimal score(Long accountId, BigDecimal amount, String currency) {
        try {
            return latencyTimer.record(() -> {
                Map<String, Object> body = restClient.post()
                        .uri("/score")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("accountId", accountId, "amount", amount, "currency", currency))
                        .retrieve()
                        .body(Map.class);
                if (body == null || body.get("score") == null) {
                    throw new FraudCheckException("fraud-check returned an empty body");
                }
                return new BigDecimal(body.get("score").toString());
            });
        } catch (FraudCheckException e) {
            throw e;
        } catch (Exception e) {
            log.error("fraud-check call failed: {}", e.getMessage());
            throw new FraudCheckException("fraud-check unavailable: " + e.getMessage(), e);
        }
    }

    public static class FraudCheckException extends RuntimeException {
        public FraudCheckException(String message) { super(message); }
        public FraudCheckException(String message, Throwable cause) { super(message, cause); }
    }
}
