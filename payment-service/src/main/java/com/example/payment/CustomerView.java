package com.example.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;

/** The part of the Customer service response that the Payment service cares about. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerView(Long id, String name, String email, BigDecimal amountDue, String status) {
}
