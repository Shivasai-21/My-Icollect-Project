package com.example.customer;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/**
 * In-memory store to keep the demo self-contained.
 * In production this would be Spring Data JPA against the customer schema in RDS MySQL.
 */
@Repository
public class CustomerRepository {

    private final Map<Long, Customer> store = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    public CustomerRepository() {
        create("Asha Rao", "asha.rao@example.com", new BigDecimal("250.00"));
        create("Ravi Kumar", "ravi.kumar@example.com", new BigDecimal("1200.50"));
        create("Meera Shah", "meera.shah@example.com", new BigDecimal("0.00"));
    }

    public Customer create(String name, String email, BigDecimal amountDue) {
        long id = sequence.incrementAndGet();
        Customer customer = new Customer(id, name, email, amountDue, "ACTIVE");
        store.put(id, customer);
        return customer;
    }

    public Optional<Customer> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<Customer> findAll() {
        return store.values().stream().sorted(Comparator.comparing(Customer::id)).toList();
    }

    /** Reduces the amount due. Returns null if the customer does not exist. */
    public Customer applyPayment(Long id, BigDecimal amount) {
        return store.computeIfPresent(id, (key, current) -> {
            if (amount.compareTo(current.amountDue()) > 0) {
                throw new IllegalArgumentException("Payment exceeds amount due");
            }
            return new Customer(current.id(), current.name(), current.email(),
                    current.amountDue().subtract(amount), current.status());
        });
    }
}
