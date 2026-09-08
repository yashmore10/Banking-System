package com.banking.accountservice.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name="accounts")
@Data
@AllArgsConstructor
@NoArgsConstructor
/*
@Builder
    we can create and populate the object in a more readable way.

    then in AccountService write
        Account account = Account.builder()
            .accountHolderName(request.getAccountHolderName())
            .email(request.getEmail())
            .phone(request.getPhone())
            .accountType(request.getAccountType())
            .balance(request.getInitialDeposit())
            .status(AccountStatus.ACTIVE)
            .build();
*/
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false,unique = true)
    private String accountNumber;

    @Column(nullable = false)
    private String accountHolderName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Column(nullable = false,precision = 15,scale = 2)
    private BigDecimal balance;

    @Column(nullable = false,precision = 15,scale = 2)
    private BigDecimal dailyTransactionLimit;

    @Column(nullable = false,precision = 15,scale = 2)
    private BigDecimal dailyOutgoingAmount = BigDecimal.ZERO;

    private LocalDate dailyLimitDate;

    @Version
    private Long version;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
