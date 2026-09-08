package com.banking.transactionservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class TransactionServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
