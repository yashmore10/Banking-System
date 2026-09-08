package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.service.AccountService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/accounts")
@Slf4j
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;


    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request,
            @RequestHeader(value = "X-User-Id", required = false) @Parameter(hidden = true) String userId){

        return ResponseEntity.status(HttpStatus.CREATED).body(accountService.createAccount(request, userId));
    }

    @GetMapping("/{accountNumber}")
    public ResponseEntity<AccountResponse> getAccount(
            @PathVariable String accountNumber,
            @RequestHeader(value = "X-User-Id", required = false) @Parameter(hidden = true) String callerUserId,
            @RequestHeader(value = "X-User-Role", required = false) @Parameter(hidden = true) String callerRole) {

        return ResponseEntity.ok(accountService.getAccount(accountNumber, callerUserId, callerRole));
    }

    @GetMapping("/{accountNumber}/balance")
    public ResponseEntity<BigDecimal> getBalance(
            @PathVariable String accountNumber,
            @RequestHeader(value = "X-User-Id", required = false) @Parameter(hidden = true) String callerUserId,
            @RequestHeader(value = "X-User-Role", required = false) @Parameter(hidden = true) String callerRole) {

        return ResponseEntity.ok(accountService.getBalance(accountNumber, callerUserId, callerRole));
    }

    @PutMapping("/{accountNumber}/block")
    public ResponseEntity<String> blockAccount(
            @PathVariable String accountNumber,
            @RequestHeader(value = "X-User-Role", required = false) @Parameter(hidden = true) String callerRole) {

        if (!"ADMIN".equals(callerRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Only admins can block accounts");
        }
        accountService.blockAccount(accountNumber);
        return ResponseEntity.ok("Account blocked successfully");
    }

    @PutMapping("/{accountNumber}/unblock")
    public ResponseEntity<String> unblockAccount(
            @PathVariable String accountNumber,
            @RequestHeader(value = "X-User-Role", required = false) @Parameter(hidden = true) String callerRole) {

        if (!"ADMIN".equals(callerRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Only admins can unblock accounts");
        }
        accountService.unblockAccount(accountNumber);
        return ResponseEntity.ok("Account unblocked successfully");
    }

    /*
        SAGA step 1: deduct balance
        called by Transaction service when transfer initiated
    */

    @PutMapping("/{accountNumber}/deduct")
    public ResponseEntity<String> deductBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount){

        accountService.deductBalance(accountNumber,amount);
        return ResponseEntity.ok("Balance deducted successfully");
    }

    /*
        SAGA step 4: compensating
        CALLED by Transaction service in 2 scenarios
        1. Fraud detected --> refund sender
        2. Transaction completed--> credit receiver
    */

    @PutMapping("/{accountNumber}/credit")
    public ResponseEntity<String> creditBalance(
            @PathVariable String accountNumber,
            @RequestParam BigDecimal amount){

        accountService.creditBalance(accountNumber,amount);
        return ResponseEntity.ok("Balance credited successfully");
    }
}
