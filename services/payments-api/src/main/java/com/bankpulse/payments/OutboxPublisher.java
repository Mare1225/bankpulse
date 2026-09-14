package com.bankpulse.payments;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Component
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxRepository outbox;
    private final RestClient auditClient;
    private final Counter failureCounter;
    private final Timer publishLatency;

    public OutboxPublisher(OutboxRepository outbox, RestClient.Builder builder,
            @Value("${audit.base-url}") String auditBaseUrl, MeterRegistry registry) {
        this.outbox = outbox;
        this.auditClient = builder.baseUrl(auditBaseUrl).build();
        // KPI 1 — eventos pendientes en el outbox (lee MariaDB en cada scrape)
        io.micrometer.core.instrument.Gauge.builder("outbox.pending", outbox, OutboxRepository::countByPublishedFalse)
                .description("Eventos pendientes de entregar a audit-api")
                .register(registry);
        // KPI 2 — latencia de entrega al audit-api
        this.publishLatency = Timer.builder("outbox.publish.latency")
                .description("Tiempo entre creación del evento y publicación exitosa")
                .register(registry);
        // KPI 3 — intentos fallidos acumulados
        this.failureCounter = Counter.builder("outbox.publish.failures")
                .description("Entregas fallidas al audit-api")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${outbox.fixed-delay-ms}")
    @Transactional
    public void publishPending() {
        for (OutboxEvent event : outbox.findTop50ByPublishedFalseOrderByCreatedAtAsc()) {
            try {
                auditClient.post()
                        .uri("/internal/events")
                        .header("X-Event-Id", event.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(event.getPayload())
                        .retrieve()
                        .toBodilessEntity();
                publishLatency.record(Duration.between(event.getCreatedAt(), Instant.now()));
                event.markPublished();
                log.info("Published eventId={} aggregateId={}", event.getId(), event.getAggregateId());
            } catch (Exception ex) {
                failureCounter.increment();
                event.markFailed(ex.getMessage());
                log.warn("Audit unavailable; eventId={} remains in outbox, attempt={}", event.getId(), event.getAttempts());
            }
        }
    }
}
