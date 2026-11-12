package com.payflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "fx_rates")
public class FxRate {

    @Id
    private String currency;

    @Column(name = "rate_to_usd", nullable = false)
    private BigDecimal rateToUsd;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getCurrency() { return currency; }
    public BigDecimal getRateToUsd() { return rateToUsd; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
