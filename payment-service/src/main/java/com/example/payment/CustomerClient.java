package com.example.payment;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Synchronous REST client for the Customer service, with explicit timeouts. */
@Component
public class CustomerClient {

    private final RestClient client;

    public CustomerClient(@Value("${services.customer.url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Returns the customer, or null when the Customer service answers 404. */
    public CustomerView get(Long id) {
        try {
            return client.get().uri("/customers/{id}", id).retrieve().body(CustomerView.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Customer service unavailable");
        }
    }

    public void applyPayment(Long id, BigDecimal amount) {
        try {
            client.post().uri("/customers/{id}/apply-payment", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("amount", amount))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not update customer balance");
        }
    }
}
