package com.banking.accountservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.banking.accountservice.entity.ProcessedEvent;
import com.banking.accountservice.repository.ProcessedEventRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountEventConsumer {

    private final AccountService accountService;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(topics = "transaction.completed")
    @Transactional
    public void consumeTransactionCompleted(
            @Payload Map<String,Object> payload){

        try {

            String receiverAccount=(String) payload.get("receiverAccountNumber");
            BigDecimal amount=new BigDecimal(payload.get("amount").toString());
            String transactionId = (String) payload.get("transactionId");
            String eventId = "transaction.completed:" + transactionId;

            if (processedEventRepository.existsById(eventId)) {
                log.info("Duplicate completion event ignored: {}", transactionId);
                return;
            }

            log.info("Crediting account: {} amount: {}",receiverAccount,amount);

            accountService.creditBalance(receiverAccount,amount);
            processedEventRepository.save(new ProcessedEvent(eventId, null));

        } catch (Exception e) {
            log.error("Error crediting account", e);
            throw new IllegalStateException("Unable to credit receiver account", e);
        }
    }

    @KafkaListener(topics = "fraud.detected")
    public void consumerFraudDetected(
            @Payload Map<String,Object> payload) {

        try {

            String accountNumber=(String) payload.get("accountNumber");
            log.info("Fraud detected - blocking account: {}",accountNumber);

            accountService.blockAccount(accountNumber);

        } catch (Exception e) {
            log.error("Error blocking account", e);
            throw new IllegalStateException("Unable to block account", e);
        }
    }
}
