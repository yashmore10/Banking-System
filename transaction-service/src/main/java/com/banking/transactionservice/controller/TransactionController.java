package com.banking.transactionservice.controller;

import com.banking.transactionservice.dto.TransactionRequest;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.service.TransactionService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/transactions")
@Slf4j
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransactionRequest request,
            @RequestHeader("X-User-Id") @Parameter(hidden = true) String callerUserId){

        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.transfer(request,callerUserId));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(
            @PathVariable String transactionId,
            @RequestHeader(value = "X-User-Id", required = false) @Parameter(hidden = true) String callerUserId,
            @RequestHeader(value = "X-User-Role", required = false) @Parameter(hidden = true) String callerRole) {

        return ResponseEntity.ok(transactionService.getTransaction(transactionId, callerUserId, callerRole));
    }

    @GetMapping("/account/{accountNumber}")
    public ResponseEntity<List<TransactionResponse>> getTransactionHistory(
            @PathVariable String accountNumber,
            @RequestHeader(value = "X-User-Id", required = false) @Parameter(hidden = true) String callerUserId,
            @RequestHeader(value = "X-User-Role", required = false) @Parameter(hidden = true) String callerRole) {

        return ResponseEntity.ok(transactionService.getTransactionHistory(accountNumber, callerUserId, callerRole));
    }

    @PostMapping("/{transactionId}/verify")
    public ResponseEntity<TransactionResponse> verifyOTP(
            @PathVariable String transactionId,
            @RequestParam String otp){
        log.info("OTP verification request - transaction: {}",transactionId);

        return ResponseEntity.ok(transactionService.verifyOTP(transactionId,otp));
    }

}
