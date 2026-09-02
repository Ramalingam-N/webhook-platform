package com.webhook.dispatcher;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import com.webhook.core.entity.Endpoint;
import com.webhook.core.repository.DeadLetterRepository;
import com.webhook.core.repository.EndpointRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertThat;

@SpringBootTest
@Testcontainers
class DispatcherE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EndpointRepository endpointRepository;
    @Autowired private DeadLetterRepository deadLetterRepository;

    private WireMockServer wireMockServer;
    private final String tenantId = "tenant-e2e";
    private final String webhookSecret = "whsec_super_secret_e2e_key";

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
        
        // Clean DB
        endpointRepository.deleteAll();
        deadLetterRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    @DisplayName("E1: Full Happy Path & Exactly-Once Idempotency Guarantee")
    void happyPathAndIdempotency() throws Exception {
        Endpoint endpoint = Endpoint.builder()
                .tenantId(tenantId)
                .url(wireMockServer.baseUrl() + "/webhook")
                .secret(webhookSecret)
                .status("ACTIVE")
                .build();
        endpointRepository.save(endpoint);

        wireMockServer.stubFor(post(urlEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200)));

        UUID eventId = UUID.randomUUID();
        String payload = "{\"data\":\"hello world\"}";

        ProducerRecord<String, String> record = new ProducerRecord<>("webhook-fast-lane", tenantId, payload);
        record.headers().add("X-Event-Id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            wireMockServer.verify(1, postRequestedFor(urlEqualTo("/webhook"))
                    .withHeader("X-Webhook-Signature", matching("sha256=[0-9a-f]+"))
                    .withRequestBody(equalToJson(payload)));
        });

        kafkaTemplate.send(record).get();

        Thread.sleep(3100);
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/webhook")));
    }

    @Test
    @DisplayName("E2: Customer API Down (500 Error) -> Payload is safely routed to Dead Letter Queue")
    void customerApiDownRoutesToDlq() throws Exception {
        // Arrange: Register a new endpoint
        String failTenant = "tenant-fail";
        Endpoint endpoint = Endpoint.builder()
                .tenantId(failTenant)
                .url(wireMockServer.baseUrl() + "/fail-webhook")
                .secret(webhookSecret)
                .status("ACTIVE")
                .build();
        endpointRepository.save(endpoint);

        wireMockServer.stubFor(post(urlEqualTo("/fail-webhook"))
                .willReturn(aResponse().withStatus(500)));

        UUID eventId = UUID.randomUUID();
        String payload = "{\"data\":\"crash test\"}";

        ProducerRecord<String, String> record = new ProducerRecord<>("webhook-fast-lane", failTenant, payload);
        record.headers().add("X-Event-Id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();

        await().atMost(50, TimeUnit.SECONDS).untilAsserted(() -> {
            wireMockServer.verify(postRequestedFor(urlEqualTo("/fail-webhook")));

            assertThat(deadLetterRepository.findByEventId(eventId)).isPresent();
            
            assertThat(deadLetterRepository.findByEventId(eventId).get().getPayload()).isEqualTo(payload);
        });
    }
}