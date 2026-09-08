package com.banking.accountservice.service;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.entity.Account;
import com.banking.accountservice.entity.AccountStatus;
import com.banking.accountservice.entity.AccountType;
import com.banking.accountservice.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;

    // to unique 12 digit generate accountNumber
    private static SecureRandom secureRandom=new SecureRandom();



    public AccountResponse createAccount(CreateAccountRequest request) {
        return createAccount(request, "SYSTEM");
    }

    public AccountResponse createAccount(CreateAccountRequest request, String userId) {
        log.info("Creating account for: {}",request.getEmail());

        // check if the email already exists or not in db
        if (accountRepository.existsByEmail(request.getEmail())){
            throw new RuntimeException("Account already exists for email: "+request.getEmail());
        }

        Account account=new Account();

        account.setUserId(userId != null && !userId.isBlank() ? userId : "SYSTEM");
        account.setAccountHolderName(request.getAccountHolderName());
        account.setEmail(request.getEmail());
        account.setPhone(request.getPhone());
        account.setAccountType(request.getAccountType());
        account.setBalance(request.getInitialDeposit());
        account.setStatus(AccountStatus.ACTIVE);

        // the accountNumber should be unique and of 12 digits so we have to write custom logic for this
        account.setAccountNumber(generateAccountNumber());

        // the dailyTransactionLimit should be according to the accountType
        account.setDailyTransactionLimit(
                request.getAccountType()== AccountType.SAVINGS ? new BigDecimal("100000"): new BigDecimal("500000")
        );

        Account savedAccount = accountRepository.save(account);
        log.info("Account created: {}",savedAccount.getAccountNumber());

        // since the return type is AccountResponse and we have type Account to return
        return mapToResponse(savedAccount);
    }

    private String generateAccountNumber() {

        String accountNumber;

        do {
            long number=secureRandom.nextLong(1_000_000_000_000L);
            accountNumber=String.format("%012d",number);
        }while (accountRepository.existsByAccountNumber(accountNumber));

        return accountNumber;
    }

    private AccountResponse mapToResponse(Account account) {

        AccountResponse response=new AccountResponse();

        response.setId(account.getId());
        response.setUserId(account.getUserId());
        response.setAccountNumber(account.getAccountNumber());
        response.setAccountHolderName(account.getAccountHolderName());
        response.setEmail(account.getEmail());
        response.setPhone(account.getPhone());
        response.setAccountType(account.getAccountType());
        response.setStatus(account.getStatus());
        response.setBalance(account.getBalance());
        response.setDailyTransactionLimit(account.getDailyTransactionLimit());
        response.setCreatedAt(account.getCreatedAt());

        return response;
    }

    public AccountResponse getAccount(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found."));
        return mapToResponse(account);
    }

    /**
     * Role-aware getAccount: ADMIN can view any account; CUSTOMER can only view their own.
     * When callerRole is null, this is an internal service-to-service call.
     */
    public AccountResponse getAccount(String accountNumber, String callerUserId, String callerRole) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));

        if (callerRole != null && !"ADMIN".equals(callerRole) && !account.getUserId().equals(callerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only view your own account");
        }
        return mapToResponse(account);
    }

    public BigDecimal getBalance(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new RuntimeException("Account not found."));
        return account.getBalance();
    }

    /**
     * Role-aware getBalance: ADMIN can view any; CUSTOMER can only view their own.
     * When callerRole is null, this is an internal service-to-service call.
     */
    public BigDecimal getBalance(String accountNumber, String callerUserId, String callerRole) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));

        if (callerRole != null && !"ADMIN".equals(callerRole) && !account.getUserId().equals(callerUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only view your own account balance");
        }
        return account.getBalance();
    }

    // CALLED by Fraud detection service via Kafka OR by admin via REST
    public void blockAccount(String accountNumber) {

        log.info("Blocking account: {}",accountNumber);

        Account account=accountRepository.findByAccountNumber(accountNumber).orElseThrow(()->new RuntimeException("Account not found."));

        account.setStatus(AccountStatus.BLOCKED);

        accountRepository.save(account);
        log.info("Account blocked: {}",account);

    }
    // CALLED by admin via REST
    public void unblockAccount(String accountNumber) {
        log.info("Unblocking account: {}", accountNumber);
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
        account.setStatus(AccountStatus.ACTIVE);
        accountRepository.save(account);
        log.info("Account unblocked: {}", accountNumber);
    }

    // CALLED by Transaction service
    public void deductBalance(String accountNumber, BigDecimal amount){

        log.info("Deducting balance {} from account: {}",amount,accountNumber);

        Account account=accountRepository.findByAccountNumber(accountNumber).orElseThrow(()->new RuntimeException("Account not found."));

        if (account.getStatus()!=AccountStatus.ACTIVE){
            throw  new RuntimeException("Account is not active: "+accountNumber);
        }

        if (account.getBalance().compareTo(amount)<0){
            throw new RuntimeException("Insufficient funds for account: "+accountNumber);
        }

        account.setBalance(account.getBalance().subtract(amount));

        accountRepository.save(account);

        log.info("Balance updated. New balance: {}",account.getBalance());
    }

    // CALLED by Transaction service via kafka
    public  void creditBalance(String accountNumber,BigDecimal amount){

        log.info("Crediting {} to account: {}",amount,accountNumber);

        Account account=accountRepository.findByAccountNumber(accountNumber).orElseThrow(()->new RuntimeException("Account not found."));

        account.setBalance(account.getBalance().add(amount));

        accountRepository.save(account);

        log.info("Balance credited. New balance: {}",account.getBalance());
    }

}
