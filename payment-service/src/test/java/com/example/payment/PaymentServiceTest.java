package com.example.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PaymentServiceTest {

    private final CustomerClient customerClient = mock(CustomerClient.class);
    private final NotificationClient notificationClient = mock(NotificationClient.class);
    private final PaymentRepository repository = new PaymentRepository();
    private final PaymentService service =
            new PaymentService(customerClient, notificationClient, repository, new SimpleMeterRegistry());

    private CustomerView customer(String status, String amountDue) {
        return new CustomerView(1001L, "Asha Rao", "asha.rao@example.com", new BigDecimal(amountDue), status);
    }

    @Test
    void successfulPaymentUpdatesBalanceRecordsPaymentAndNotifies() {
        when(customerClient.get(1001L)).thenReturn(customer("ACTIVE", "250.00"));

        Payment payment = service.makePayment(new PaymentRequest(1001L, new BigDecimal("100.00"), "CARD"));

        assertEquals("SUCCESS", payment.status());
        assertEquals(1, repository.findAll().size());
        verify(customerClient).applyPayment(1001L, new BigDecimal("100.00"));
        verify(notificationClient).sendPaymentReceipt(any(CustomerView.class), any(Payment.class));
    }

    @Test
    void unknownCustomerIsRejectedWith404() {
        when(customerClient.get(1001L)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.makePayment(new PaymentRequest(1001L, new BigDecimal("10.00"), "CARD")));

        assertEquals(404, ex.getStatusCode().value());
        verify(customerClient, never()).applyPayment(any(), any());
    }

    @Test
    void inactiveCustomerIsRejectedWith409() {
        when(customerClient.get(1001L)).thenReturn(customer("SUSPENDED", "250.00"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.makePayment(new PaymentRequest(1001L, new BigDecimal("10.00"), "CARD")));

        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void paymentAboveAmountDueIsRejectedWith400() {
        when(customerClient.get(1001L)).thenReturn(customer("ACTIVE", "50.00"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.makePayment(new PaymentRequest(1001L, new BigDecimal("50.01"), "CARD")));

        assertEquals(400, ex.getStatusCode().value());
        verify(notificationClient, never()).sendPaymentReceipt(any(), any());
    }
}
