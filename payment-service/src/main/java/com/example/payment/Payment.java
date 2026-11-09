package com.example.payment;

import java.math.BigDecimal;
import java.time.Instant;

public record Payment(String id, Long customerId, BigDecimal amount, String method,
                      String status, Instant createdAt) {
}
