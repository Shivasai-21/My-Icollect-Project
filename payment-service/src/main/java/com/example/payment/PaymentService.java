package com.example.payment;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PaymentService {

    private final CustomerClient customerClient;
    private final NotificationClient notificationClient;
    private final PaymentRepository repository;
    private final MeterRegistry meterRegistry;

    public PaymentService(CustomerClient customerClient, NotificationClient notificationClient,
                          PaymentRepository repository, MeterRegistry meterRegistry) {
        this.customerClient = customerClient;
        this.notificationClient = notificationClient;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    public Payment makePayment(PaymentRequest request) {
        // 1. Validate the customer through the Customer service
        CustomerView customer = customerClient.get(request.customerId());
        if (customer == null) {
            throw reject(HttpStatus.NOT_FOUND, "Customer not found");
        }
        if (!"ACTIVE".equals(customer.status())) {
            throw reject(HttpStatus.CONFLICT, "Customer is not active");
        }
        if (request.amount().compareTo(customer.amountDue()) > 0) {
            throw reject(HttpStatus.BAD_REQUEST, "Amount exceeds amount due");
        }

        // 2. Update the customer's balance, then record the payment
        customerClient.applyPayment(customer.id(), request.amount());
        Payment payment = new Payment(UUID.randomUUID().toString(), customer.id(),
                request.amount(), request.method(), "SUCCESS", Instant.now());
        repository.save(payment);
        meterRegistry.counter("icollect_payments_total", "status", "success").increment();

        // 3. Tell the Notification service (best effort)
        notificationClient.sendPaymentReceipt(customer, payment);
        return payment;
    }

    private ResponseStatusException reject(HttpStatus status, String message) {
        meterRegistry.counter("icollect_payments_total", "status", "rejected").increment();
        return new ResponseStatusException(status, message);
    }
}
