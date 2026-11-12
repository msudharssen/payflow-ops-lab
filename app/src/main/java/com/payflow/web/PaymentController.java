package com.payflow.web;

import com.payflow.domain.Payment;
import com.payflow.service.PaymentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public record CreatePaymentRequest(
            @NotNull Long accountId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    @PostMapping("/payments")
    public ResponseEntity<Payment> create(@Valid @RequestBody CreatePaymentRequest request) {
        Payment payment = paymentService.create(request.accountId(), request.amount(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(payment);
    }

    @GetMapping("/payments/{id}")
    public Payment get(@PathVariable Long id) {
        return paymentService.get(id);
    }

    @GetMapping("/payments")
    public List<Map<String, Object>> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return paymentService.search(status, accountId, from, to);
    }

    @GetMapping("/accounts/{id}/balance")
    public Map<String, Object> balance(@PathVariable Long id) {
        return paymentService.balance(id);
    }
}
