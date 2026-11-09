package com.example.customer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    public record CreateCustomerRequest(
            @NotBlank String name,
            @NotBlank @Email String email,
            @NotNull @DecimalMin("0.00") BigDecimal amountDue) {
    }

    public record ApplyPaymentRequest(@NotNull @DecimalMin("0.01") BigDecimal amount) {
    }

    private final CustomerRepository repository;

    public CustomerController(CustomerRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Customer> list() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public Customer get(@PathVariable Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Customer create(@Valid @RequestBody CreateCustomerRequest request) {
        return repository.create(request.name(), request.email(), request.amountDue());
    }

    /** Called by the Payment service after a successful payment. */
    @PostMapping("/{id}/apply-payment")
    public Customer applyPayment(@PathVariable Long id, @Valid @RequestBody ApplyPaymentRequest request) {
        try {
            Customer updated = repository.applyPayment(id, request.amount());
            if (updated == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
            }
            return updated;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
