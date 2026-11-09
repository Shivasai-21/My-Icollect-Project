package com.example.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CustomerRepositoryTest {

    @Test
    void applyPaymentReducesAmountDue() {
        CustomerRepository repo = new CustomerRepository();
        Customer c = repo.create("Test User", "test@example.com", new BigDecimal("100.00"));

        Customer updated = repo.applyPayment(c.id(), new BigDecimal("40.00"));

        assertEquals(new BigDecimal("60.00"), updated.amountDue());
    }

    @Test
    void applyPaymentRejectsOverpayment() {
        CustomerRepository repo = new CustomerRepository();
        Customer c = repo.create("Test User", "test@example.com", new BigDecimal("10.00"));

        assertThrows(IllegalArgumentException.class,
                () -> repo.applyPayment(c.id(), new BigDecimal("10.01")));
    }

    @Test
    void applyPaymentUnknownCustomerReturnsNull() {
        CustomerRepository repo = new CustomerRepository();

        assertNull(repo.applyPayment(999999L, new BigDecimal("1.00")));
    }
}
