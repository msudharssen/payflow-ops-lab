package com.payflow.service;

import com.payflow.domain.Account;
import com.payflow.domain.FxRate;
import com.payflow.domain.Payment;
import com.payflow.repo.AccountRepository;
import com.payflow.repo.FxRateRepository;
import com.payflow.repo.PaymentRepository;
import com.payflow.web.TraceIdFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;
    private final FxRateRepository fxRateRepository;
    private final FraudCheckClient fraudCheckClient;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry registry;
    private final Counter paymentsCreated;
    private final BigDecimal rejectThreshold;

    public PaymentService(PaymentRepository paymentRepository,
                          AccountRepository accountRepository,
                          FxRateRepository fxRateRepository,
                          FraudCheckClient fraudCheckClient,
                          JdbcTemplate jdbcTemplate,
                          MeterRegistry registry,
                          @Value("${payflow.fraud-check.reject-threshold}") BigDecimal rejectThreshold) {
        this.paymentRepository = paymentRepository;
        this.accountRepository = accountRepository;
        this.fxRateRepository = fxRateRepository;
        this.fraudCheckClient = fraudCheckClient;
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
        this.rejectThreshold = rejectThreshold;
        this.paymentsCreated = Counter.builder("payments_created_total")
                .description("Payments accepted (PENDING or REJECTED by fraud score)")
                .register(registry);
    }

    public Payment create(Long accountId, BigDecimal amount, String currency) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> failed("account_not_found",
                        new NotFoundException("account " + accountId + " not found")));
        if (!"ACTIVE".equals(account.getStatus())) {
            throw failed("account_inactive",
                    new ValidationException("account " + accountId + " is " + account.getStatus()));
        }
        FxRate rate = fxRateRepository.findById(currency)
                .orElseThrow(() -> failed("unknown_currency",
                        new ValidationException("no FX rate for currency " + currency)));

        BigDecimal fraudScore;
        try {
            fraudScore = fraudCheckClient.score(accountId, amount, currency);
        } catch (FraudCheckClient.FraudCheckException e) {
            throw failed("fraud_check_unavailable", e);
        }

        Payment payment = new Payment();
        payment.setAccountId(accountId);
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setAmountUsd(amount.multiply(rate.getRateToUsd()).setScale(2, RoundingMode.HALF_UP));
        payment.setFraudScore(fraudScore);
        payment.setStatus(fraudScore.compareTo(rejectThreshold) >= 0 ? "REJECTED" : "PENDING");
        payment.setTraceId(MDC.get(TraceIdFilter.TRACE_ID));
        payment.setCreatedAt(LocalDateTime.now());
        payment = paymentRepository.save(payment);

        paymentsCreated.increment();
        log.info("payment created",
                kv("paymentId", payment.getPaymentId()),
                kv("status", payment.getStatus()),
                kv("fraudScore", fraudScore));
        return payment;
    }

    public Payment get(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("payment " + paymentId + " not found"));
    }

    /** Raw JDBC search — the query that goes slow without IX_PAY_STATUS_CREATED. */
    public List<Map<String, Object>> search(String status, Long accountId,
                                            LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder(
                "SELECT payment_id, account_id, amount, currency, amount_usd, status, "
                + "fraud_score, trace_id, created_at, settled_at FROM payments WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null) { sql.append(" AND status = ?"); params.add(status); }
        if (accountId != null) { sql.append(" AND account_id = ?"); params.add(accountId); }
        if (from != null) { sql.append(" AND created_at >= ?"); params.add(from); }
        if (to != null) { sql.append(" AND created_at < ?"); params.add(to); }
        sql.append(" ORDER BY created_at DESC FETCH FIRST 100 ROWS ONLY");
        return jdbcTemplate.queryForList(sql.toString(), params.toArray());
    }

    /** Balance = sum of settled payments in USD. */
    public Map<String, Object> balance(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("account " + accountId + " not found"));
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT NVL(SUM(amount_usd), 0) FROM payments WHERE account_id = ? AND status = 'SETTLED'",
                BigDecimal.class, accountId);
        return Map.of(
                "accountId", accountId,
                "name", account.getName(),
                "currency", account.getCurrency(),
                "balanceUsd", balance);
    }

    private RuntimeException failed(String reason, RuntimeException cause) {
        registry.counter("payments_failed_total", "reason", reason).increment();
        return cause;
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class ValidationException extends RuntimeException {
        public ValidationException(String message) { super(message); }
    }
}
