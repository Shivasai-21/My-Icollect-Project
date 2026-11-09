package com.example.customer;

import java.math.BigDecimal;

/** A customer with an outstanding balance that the Payment service collects against. */
public record Customer(Long id, String name, String email, BigDecimal amountDue, String status) {
}
