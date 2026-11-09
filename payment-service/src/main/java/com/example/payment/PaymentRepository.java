package com.example.payment;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

/** In-memory store for the demo. In production: Spring Data JPA on the payment schema in RDS. */
@Repository
public class PaymentRepository {

    private final List<Payment> payments = new CopyOnWriteArrayList<>();

    public Payment save(Payment payment) {
        payments.add(payment);
        return payment;
    }

    public List<Payment> findAll() {
        return List.copyOf(payments);
    }

    public List<Payment> findByCustomerId(Long customerId) {
        return payments.stream().filter(p -> p.customerId().equals(customerId)).toList();
    }

    public Optional<Payment> findById(String id) {
        return payments.stream().filter(p -> p.id().equals(id)).findFirst();
    }
}
