package com.banking.transactionservice.service;

import com.banking.transactionservice.client.AccountServiceClient;
import com.banking.transactionservice.dto.AccountResponse;
import com.banking.transactionservice.dto.TransactionRequest;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.entity.Transaction;
import com.banking.transactionservice.entity.TransactionStatus;
import com.banking.transactionservice.entity.TransactionType;
import com.banking.transactionservice.event.TransactionCompletedEvent;
import com.banking.transactionservice.event.TransactionInitiatedEvent;
import com.banking.transactionservice.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountServiceClient accountServiceClient;

    private final KafkaTemplate<String,Object> kafkaTemplate;
    private final RedisTemplate<String,String> redisTemplate;

    private static final String TRANSACTION_INITIATED_TOPIC="transaction.initiated";
    private static final String TRANSACTION_COMPLETED_TOPIC="transaction.completed";
    private static final String TRANSACTION_REFUNDED_TOPIC="transaction.refunded";
    private static final String FRAUD_DETECTED_TOPIC="fraud.detected";

/*
SAGA step 1: Initiate transfer
    deducts from sender via feign
    saves transaction status as PROCESSING
    publishes event to kafka for fraud check
 */
    public TransactionResponse transfer(TransactionRequest request,String callerUserId){

        // Fetch the sender account
        // Check that the account belongs to the caller
        AccountResponse senderAccount = accountServiceClient.getAccount(request.getSenderAccountNumber());
        if (senderAccount == null || !callerUserId.equals(senderAccount.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only transfer from your own account");
        }

        log.info("SAGA start - Transfer: {} -> {}, amount:{}",
                request.getSenderAccountNumber(),
                request.getReceiverAccountNumber(),request.getAmount());

        if (request.getSenderAccountNumber().equals(request.getReceiverAccountNumber())) {
            throw new IllegalArgumentException("Sender and receiver accounts must be different");
        }

        BigDecimal senderBalanceBeforeDebit = accountServiceClient
                .getBalance(request.getSenderAccountNumber());

        Transaction transaction =new Transaction();
        transaction.setSenderAccountNumber(request.getSenderAccountNumber());
        transaction.setReceiverAccountNumber(request.getReceiverAccountNumber());
        transaction.setAmount(request.getAmount());
        transaction.setType(TransactionType.TRANSFER);
        transaction.setStatus(TransactionStatus.PENDING);
        transaction.setDescription(request.getDescription());
        transaction.setReferenceNumber(UUID.randomUUID().toString());

        Transaction savedTransaction=transactionRepository.save(transaction);

        boolean senderDebited = false;

        try {
            // SAGA step 1: deduct from sender only after a recoverable record exists.
            accountServiceClient.deductBalance(
                    request.getSenderAccountNumber(),
                    request.getAmount()
            );
            senderDebited = true;

            savedTransaction.setStatus(TransactionStatus.PROCESSING);
            savedTransaction = transactionRepository.save(savedTransaction);

            log.info("Transaction saved as PROCESSING: {}",savedTransaction.getId());

            TransactionInitiatedEvent event=new TransactionInitiatedEvent(
                    savedTransaction.getId(),
                    savedTransaction.getSenderAccountNumber(),
                    savedTransaction.getReceiverAccountNumber(),
                    savedTransaction.getAmount(),
                    senderBalanceBeforeDebit,
                    savedTransaction.getDescription());

            kafkaTemplate.send(TRANSACTION_INITIATED_TOPIC, savedTransaction.getId(), event).get();
            log.info("TransactionInitiatedEvent published: {}",savedTransaction.getId());

            return mapToResponse(savedTransaction);
        } catch (Exception exception) {
            if (senderDebited) {
                try {
                    accountServiceClient.creditBalance(
                            savedTransaction.getSenderAccountNumber(),
                            savedTransaction.getAmount()
                    );
                    savedTransaction.setStatus(TransactionStatus.FAILED);
                    savedTransaction.setFailureReason(
                            "Transfer initiation failed and the sender was refunded: "
                                    + exception.getMessage()
                    );
                } catch (Exception compensationException) {
                    savedTransaction.setStatus(TransactionStatus.FLAGGED);
                    savedTransaction.setFailureReason(
                            "Transfer initiation failed; automatic refund also failed: "
                                    + compensationException.getMessage()
                    );
                    log.error("Manual recovery required for transaction {}", savedTransaction.getId(), compensationException);
                }
            } else {
                savedTransaction.setStatus(TransactionStatus.FAILED);
                savedTransaction.setFailureReason("Sender debit failed: " + exception.getMessage());
            }

            transactionRepository.save(savedTransaction);
            throw new IllegalStateException("Unable to initiate transfer", exception);
        }
    }

    public TransactionResponse getTransaction(String transactionId) {
        return mapToResponse(transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException(
                        "Transaction not found: " + transactionId
                )));
    }

    /**
     * Role-aware: ADMIN sees any transaction; CUSTOMER must be the sender's account owner.
     */
    public TransactionResponse getTransaction(String transactionId, String callerUserId, String callerRole) {
        Transaction tx = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Transaction not found: " + transactionId));

        if (!"ADMIN".equals(callerRole)) {
            // Verify caller owns the sender account
            AccountResponse senderAccount = accountServiceClient.getAccount(tx.getSenderAccountNumber());
            if (senderAccount == null || !callerUserId.equals(senderAccount.getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You can only view your own transactions");
            }
        }
        return mapToResponse(tx);
    }

    public List<TransactionResponse> getTransactionHistory(String accountNumber) {
        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Role-aware: ADMIN sees any account history; CUSTOMER must own the account.
     */
    public List<TransactionResponse> getTransactionHistory(String accountNumber, String callerUserId, String callerRole) {
        if (!"ADMIN".equals(callerRole)) {
            AccountResponse account = accountServiceClient.getAccount(accountNumber);
            if (account == null || !callerUserId.equals(account.getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You can only view your own account's transaction history");
            }
        }
        return transactionRepository.findBySenderAccountNumberOrderByCreatedAtDesc(accountNumber)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    private TransactionResponse mapToResponse(Transaction transaction) {

        TransactionResponse response = new TransactionResponse();
        response.setId(transaction.getId());
        response.setSenderAccountNumber(
                transaction.getSenderAccountNumber());
        response.setReceiverAccountNumber(
                transaction.getReceiverAccountNumber());
        response.setAmount(transaction.getAmount());
        response.setType(transaction.getType());
        response.setStatus(transaction.getStatus());
        response.setDescription(transaction.getDescription());
        response.setReferenceNumber(
                transaction.getReferenceNumber());
        response.setFailureReason(transaction.getFailureReason());
        response.setCreatedAt(transaction.getCreatedAt());
        response.setCompletedAt(transaction.getCompletedAt());

        return response;
    }

    @Transactional
    public TransactionResponse verifyOTP(String transactionId,String otp){

        log.info("OTP verification for the transaction: {}",transactionId);

        Transaction transaction=transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(()-> new RuntimeException("Transaction not found "+transactionId));

        if (transaction.getStatus() != TransactionStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException(
                    "Transaction is not awaiting OTP verification: " + transactionId
            );
        }

        String otpKey="verification:otp"+transactionId;
        String storedOtp=redisTemplate.opsForValue().get(otpKey);

        if (storedOtp==null){
            // OTP expired
            log.warn("OTP expired for transaction: {}",transactionId);
            compensateTransaction(transaction, "OTP expired - Transaction cancelled and amount refunded.");

            return mapToResponse(transaction);
        }

        if (!storedOtp.equals(otp)){
            // Block account and refund
            log.warn("Wrong OTP - blocking account and refunding: {}",transactionId);
            redisTemplate.delete(otpKey);

            blockAccountAndCompensate(transaction,
                    "Wrong OTP entered - transaction cancelled, "+
                    "Account blocked for security.");

            return mapToResponse(transaction);
        }

        // OTP correct - complete transaction
        log.info("OTP verified - completing transaction: {}",transactionId);
        redisTemplate.delete(otpKey);

        completeTransaction(transaction);

        return mapToResponse(transaction);
    }

    private void completeTransaction(Transaction transaction) {

        transaction.setStatus(TransactionStatus.COMPLETED);
        transaction.setCompletedAt(LocalDateTime.now());
        transaction.setVerificationExpiresAt(null);

        transactionRepository.save(transaction);

        TransactionCompletedEvent completedEvent= new TransactionCompletedEvent(
                transaction.getId(),
                transaction.getSenderAccountNumber(),
                transaction.getReceiverAccountNumber(),
                transaction.getAmount(),
                transaction.getDescription()
        );

        kafkaTemplate.send(TRANSACTION_COMPLETED_TOPIC,transaction.getId(),completedEvent);

        log.info("SAGA COMPLETED - transaction completed: {}",transaction.getId());
    }

    private void blockAccountAndCompensate(Transaction transaction, String reason) {

        // Publish fraud.detected, Account Service will block account
        Map<String,Object> fraudEvent=new HashMap<>();
        fraudEvent.put("transactionId",transaction.getId());
        fraudEvent.put("accountNumber",transaction.getSenderAccountNumber());
        fraudEvent.put("reason",reason);

        kafkaTemplate.send(FRAUD_DETECTED_TOPIC,transaction.getSenderAccountNumber(),fraudEvent);

        // SAGA compensation - refund sender
        compensateTransaction(transaction,reason);
    }

    private void compensateTransaction(Transaction transaction, String reason) {

        log.warn("SAGA COMPENSATION - refunding: {} amount: {}"
                ,transaction.getSenderAccountNumber(),transaction.getAmount());

        // credit money back to sender via feign client
        accountServiceClient.creditBalance(transaction.getSenderAccountNumber()
                ,transaction.getAmount());

        transaction.setStatus(TransactionStatus.FLAGGED);
        transaction.setVerificationExpiresAt(null);
        transaction.setFailureReason(reason+
                " - SAGA Compensation executed, amount refunded at "+ LocalDateTime.now());

        transactionRepository.save(transaction);

        // Publish refund event - Notification service will alert user
        Map<String,Object> refundEvent=new HashMap<>();
        refundEvent.put("transactionId",transaction.getId());
        refundEvent.put("senderAccountNumber",transaction.getSenderAccountNumber());
        refundEvent.put("amount",transaction.getAmount());
        refundEvent.put("reason",reason);

        kafkaTemplate.send(TRANSACTION_REFUNDED_TOPIC,transaction.getId(),refundEvent);

        log.info("SAGA COMPENSATION COMPLETE - {} refunded to {}",
                transaction.getAmount(),transaction.getSenderAccountNumber());
    }


    @Transactional
    public void processCleanResult(String transactionId) {

        Transaction transaction=transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(()-> new RuntimeException("Transaction not found "+transactionId));

        if(transaction.getStatus()!= TransactionStatus.PROCESSING){
            log.warn("Transaction {} not processing - skipped",transactionId);
            return;
        }

        completeTransaction(transaction);
    }

    @Transactional
    public void expirePendingVerifications() {
        List<Transaction> expiredTransactions = transactionRepository
                .findByStatusAndVerificationExpiresAtBefore(
                        TransactionStatus.PENDING_VERIFICATION,
                        LocalDateTime.now()
                );

        for (Transaction transaction : expiredTransactions) {
            log.warn("OTP expired for transaction: {}", transaction.getId());
            redisTemplate.delete("verification:otp" + transaction.getId());
            compensateTransaction(
                    transaction,
                    "OTP expired - Transaction cancelled and amount refunded."
            );
        }
    }
}
