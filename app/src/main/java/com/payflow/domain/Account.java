package com.payflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_id")
    private Long accountId;

    private String name;

    private String currency;

    private String status;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getAccountId() { return accountId; }
    public String getName() { return name; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
