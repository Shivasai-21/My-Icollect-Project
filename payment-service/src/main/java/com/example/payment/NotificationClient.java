package com.example.payment;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Best-effort call to the Node.js Notification service.
 * A failed notification must never fail a payment that was already recorded.
 * In production this would be an event on SNS/SQS instead of a direct HTTP call.
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestClient client;

    public NotificationClient(@Value("${services.notification.url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public void sendPaymentReceipt(CustomerView customer, Payment payment) {
        Map<String, Object> body = Map.of(
                "type", "PAYMENT_RECEIVED",
                "customerId", customer.id(),
                "email", customer.email(),
                "paymentId", payment.id(),
                "amount", payment.amount(),
                "message", "Payment of " + payment.amount() + " received. Thank you, " + customer.name() + ".");
        try {
            client.post().uri("/notifications")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Notification failed for payment {} (payment is already recorded): {}",
                    payment.id(), e.getMessage());
        }
    }
}
