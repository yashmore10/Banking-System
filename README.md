# Banking System Microservices

A distributed banking platform implementing core banking operations, event-driven distributed transactions via the SAGA pattern, automated fraud analysis, and role-based access control.

---

## Tech Stack

| Component / Layer | Technology | Purpose |
|---|---|---|
| **Language & Runtime** | Java 21 (Eclipse Temurin) | Core application programming language and runtime |
| **Framework** | Spring Boot 3.4 / 4.x, Spring Cloud 2024 | Microservice architecture, dependency injection, and HTTP APIs |
| **API Gateway** | Spring Cloud Gateway (WebFlux / Netty) | Non-blocking reverse proxy, JWT authentication boundary, rate limiting |
| **Inter-Service Communication** | Spring Cloud OpenFeign | Declarative synchronous HTTP client for internal service queries |
| **Message Broker** | Apache Kafka | Event streaming for SAGA orchestration, fraud events, and notifications |
| **Relational Database** | MySQL 8.0 | ACID-compliant persistent storage for user accounts, ledgers, and transactions |
| **In-Memory Cache & Store** | Redis | Rate limiting counter storage, OTP storage with TTL, and velocity tracking |
| **Persistence / ORM** | Spring Data JPA / Hibernate | Object-relational mapping, schema management, and row-level locking |
| **Containerization** | Docker, Docker Compose | Container packaging, multi-service orchestration, and deployment |
| **API Documentation** | SpringDoc OpenAPI (Swagger UI) | OpenAPI v3 specification generation and interactive endpoint testing |

---

## Architecture Overview

The system consists of five microservices and supporting infrastructure running within a dedicated virtual network:

[screenshot: architecture diagram - service boundaries, event topics, and data stores]

```
                  ┌────────────────────────┐
                  │      HTTP Client       │
                  │   (Browser / Swagger)  │
                  └───────────┬────────────┘
                              │ :8080
                              ▼
                  ┌────────────────────────┐
                  │      API Gateway       │  <──>  [ Redis: Rate Limiting ]
                  │   (JwtAuthFilter)      │
                  └──────┬──────────┬──────┘
                         │          │
         ┌───────────────┘          └────────────────┐
         │ :8081                                     │ :8082
         ▼                                           ▼
┌──────────────────┐                       ┌──────────────────┐
│  Account Service │                       │Transaction Service│
│  (Users/Accounts)│                       │ (Ledger & SAGA)  │
└────────┬─────────┘                       └────────┬─────────┘
         │                                          │
         │  MySQL (account_db)                      │  MySQL (transaction_db)
         │                                          │
         └──────────────┬───────────────────────────┘
                        │
                        ▼ Apache Kafka Events
        ┌───────────────┼───────────────────────────┐
        │               │                           │
        ▼               ▼                           ▼
┌──────────────┐ ┌──────────────┐            ┌──────────────┐
│  Kafka Topic │ │  Kafka Topic │            │  Kafka Topic │
│transaction.  │ │transaction.  │            │fraud.detected│
│  initiated   │ │ completed /  │            │              │
│              │ │   refunded   │            │              │
└───────┬──────┘ └──────┬───────┘            └──────┬───────┘
        │               │                           │
        ▼               ▼                           ▼
┌─────────────────────────────────┐         ┌───────────────────┐
│     Fraud Detection Service     │         │Notification Service│
│   (Redis Velocity & Limits)     │         │ (Event Consumer)  │
└─────────────────────────────────┘         └───────────────────┘
```

1. **API Gateway (`:8080`)**: Entry point for all external traffic. Validates JWT tokens, strips untrusted client headers, injects verified caller identity (`X-User-Id`, `X-User-Role`), and enforces Redis token-bucket rate limits.
2. **Account Service (`:8081`)**: Manages user identity, password hashing, account balances, and status (ACTIVE, BLOCKED). Houses the database seed routine for administrative bootstrap.
3. **Transaction Service (`:8082`)**: Coordinates fund transfers, manages ledger states, initiates SAGA execution flows, and handles OTP verification and compensation rollbacks.
4. **Fraud Detection Service (`:8084`)**: Evaluates transfer patterns asynchronously against transaction velocity limits and balance percentage thresholds. Dispatches clean, suspicious, or fraudulent outcome events.
5. **Notification Service (`:8085`)**: Consumes transaction lifecycle and refund events from Kafka, simulating SMS and email delivery to customers.
6. **Infrastructure**:
   - **Kafka & Zookeeper**: Distributed event bus enabling asynchronous orchestration and decoupled event handling.
   - **Redis**: Fast key-value store for velocity sliding windows, rate-limiting tokens, and short-lived OTP verification codes.
   - **MySQL**: Relational storage split into domain databases (`account_db` and `transaction_db`).

---

## Key Implementation Mechanisms

### 1. JWT Authentication Flow
* **What it does**: Users authenticate via `POST /api/v1/auth/login` using credentials verified against BCrypt password hashes. On successful authentication, the server returns an HMAC-SHA256 signed JSON Web Token containing the user ID (`sub`), email, and role.
* **Gateway integration**: The gateway's `JwtAuthFilter` intercepts non-public requests, validates signature and expiration, and extracts the claims. It removes any client-supplied `X-User-Id` or `X-User-Role` headers to eliminate header spoofing, then injects verified values before routing the request downstream.
* **Why it was built this way**: Centralizing token validation at the gateway keeps individual microservices lightweight. Downstream services do not need to parse or cryptographically verify tokens on each internal hop; they consume trusted headers injected exclusively by the network boundary.


---

### 2. Role-Based Access Control (RBAC) & IDOR Protection
* **What it does**: Endpoints enforce access rules using the `X-User-Role` and `X-User-Id` headers:
  - Account blocking and unblocking (`PUT /api/v1/accounts/{accountNumber}/block`, `/unblock`) require `X-User-Role: ADMIN`. Regular customers receive `403 Forbidden`.
  - Account balance and detail retrieval (`GET /api/v1/accounts/{accountNumber}`) require the caller to either be an `ADMIN` or own the account (`account.userId == callerUserId`).
  - Transaction retrieval (`GET /api/v1/transactions/{id}`) and account history (`GET /api/v1/transactions/account/{accountNumber}`) restrict access to the account owner or an `ADMIN`.
* **Internal service calls**: Feign client requests between services (such as balance lookups during transfer execution) operate on the internal network without user role headers. Domain services verify user ownership only when requests carry user identity injected by the gateway.
* **Why it was built this way**: In banking applications, Insecure Direct Object Reference (IDOR) vulnerabilities allow attackers to view or drain third-party accounts simply by guessing account numbers. Combining gateway sanitization with domain-level ownership validation guarantees defense-in-depth without binding business services to a heavy servlet-based security context.


---

### 3. SAGA Distributed Transaction Orchestration
* **What it does**: Multi-service transfers execute through a choreographed SAGA with automated compensations:
  1. **Initiate**: `TransactionService` writes a persistent `Transaction` entity in state `PENDING`, deducts the transfer amount from the sender via synchronous Feign call, transitions status to `PROCESSING`, and emits `TransactionInitiatedEvent` to Kafka.
  2. **Analyze**: `FraudDetectionService` evaluates the transaction against velocity (max transfers per minute) and risk limits (>90% of balance or 5x average).
  3. **Complete**: If clean, `TransactionCompletedEvent` triggers credit to the recipient account.
  4. **Compensate (Rollback)**: If debiting fails, if fraud is detected, or if an incorrect OTP is entered, a compensating transaction executes immediately: the sender is credited back via `creditBalance`, status changes to `FLAGGED` or `FAILED`, and the account is blocked if security was compromised.
* **Why it was built this way**: Distributed 2-phase commit (2PC) protocols do not scale across independent microservices and lock database resources across network boundaries. The SAGA pattern maintains high throughput and data consistency through decoupled, forward-moving operations backed by explicit, automated compensating steps.

---

### 4. Idempotency & Deduplication
* **What it does**:
  - Every transaction receives a generated UUID `referenceNumber` upon creation.
  - SAGA step 1 persists the transaction record prior to executing the external debit call.
  - `account_db` maintains a `processed_events` table (`event_id`, `event_type`, `processed_at`). Kafka consumers record processed event IDs in this table within the local database transaction. If an event is re-delivered, the consumer skips duplicate processing.
* **Why it was built this way**: In event-driven systems using distributed message brokers, network retries and rebalances can cause duplicate delivery ("at-least-once" delivery semantics). Explicit event deduplication tables prevent duplicate credits or debits from executing twice.

---

### 5. Atomic Transaction Handling (`@Transactional`)
* **What it does**: Business methods that modify database state (such as `verifyOTP`, `processCleanResult`, and user registration) are annotated with Spring's `@Transactional`.
* **Why it was built this way**: Relational database operations must guarantee atomicity within service boundaries. If a failure occurs mid-execution (for example, updating a transaction status succeeded but publishing an event threw an unexpected runtime exception), local database changes roll back completely, avoiding orphaned or corrupted state records.

---

### 6. Concurrency Handling & Row-Level Locking
* **What it does**:
  - `Transaction` entities declare a `@Version private Long version;` field for optimistic locking during routine status updates.
  - During critical state transitions (such as `verifyOTP`), `TransactionRepository` uses pessimistic locking (`SELECT ... FOR UPDATE` via `findByIdForUpdate`).
* **Why it was built this way**: Financial ledgers cannot tolerate race conditions. If an OTP verification request arrives at the exact millisecond an automated expiration scheduler runs, row-level pessimistic locking serializes the operations. Only one process acquires the lock, ensuring the transaction cannot be concurrently completed and refunded.

---

### 7. Fixed-Point Money Handling with `BigDecimal`
* **What it does**: All balance, deposit, transfer, limit, and calculation fields across models, DTOs, and databases use `java.math.BigDecimal` (mapped to MySQL `DECIMAL(15, 2)`).
* **Why it was built this way**: Primitive binary floating-point types (`float`, `double`) represent numbers in base-2 IEEE 754 format, causing fractional rounding errors (e.g., `0.1 + 0.2 = 0.30000000000000004`). In financial accounting, floating-point math leads to balance drift and audit failures. `BigDecimal` performs exact base-10 arithmetic.

---

### 8. Redis Velocity Tracking, Rate Limiting & OTP Storage
* **What it does**:
  - **Gateway Rate Limiting**: Uses Redis token-bucket algorithms (`RequestRateLimiter`) to allow bursts up to 20 requests with a replenishment rate of 10 requests/sec per route.
  - **Velocity Checks**: `FraudDetectionService` tracks user transaction counts in Redis using sliding window keys (`fraud:velocity:<accountNumber>`) with automatic time-to-live expirations.
  - **OTP Validation**: Suspicious transfers generate a 6-digit cryptographic OTP stored under `verification:otp<transactionId>` with a 5-minute TTL.
* **Why it was built this way**: Relational databases are poorly suited for high-frequency counters and ephemeral tokens. Redis handles sub-millisecond atomic counter increments (`INCR`) and key expirations in memory, offloading transient load from primary database storage.

---

## API Overview

### Authentication (`/api/v1/auth`)
| Method | Endpoint | Access | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Public | Register new customer account (`role: CUSTOMER`) |
| `POST` | `/api/v1/auth/login` | Public | Authenticate credentials and receive Bearer JWT token |

### Accounts (`/api/v1/accounts`)
| Method | Endpoint | Access | Description |
|---|---|---|---|
| `POST` | `/api/v1/accounts` | Customer / Admin | Create a bank account linked to the authenticated user ID |
| `GET` | `/api/v1/accounts/{accountNumber}` | Owner / Admin | Retrieve full account details |
| `GET` | `/api/v1/accounts/{accountNumber}/balance` | Owner / Admin | Retrieve current numeric balance |
| `PUT` | `/api/v1/accounts/{accountNumber}/block` | Admin Only | Manually block an account |
| `PUT` | `/api/v1/accounts/{accountNumber}/unblock` | Admin Only | Manually unblock an account |

### Transactions (`/api/v1/transactions`)
| Method | Endpoint | Access | Description |
|---|---|---|---|
| `POST` | `/api/v1/transactions/transfer` | Account Owner | Initiate fund transfer across accounts via SAGA |
| `POST` | `/api/v1/transactions/{id}/verify?otp={code}` | Account Owner | Verify OTP for suspicious transaction |
| `GET` | `/api/v1/transactions/{id}` | Sender / Admin | Retrieve transaction details and status |
| `GET` | `/api/v1/transactions/account/{accountNumber}` | Owner / Admin | Retrieve full transaction history for an account |


---

## Setup & Running Instructions

### Prerequisites
- Docker & Docker Compose
- Java 21 JDK (if building or running outside Docker)
- MySQL 8.0 (if running outside Docker)

---

### Step 1: Clone and Configure Environment

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd banking-system
   ```

2. Create your `.env` file from the provided template:
   ```powershell
   Copy-Item .env.example .env
   ```
   *(Or on Linux/macOS: `cp .env.example .env`)*

3. Edit `.env` with your desired configuration:
   ```properties
   MYSQL_USERNAME=root
   MYSQL_PASSWORD=your_mysql_password
   PUBLIC_API_BASE_URL=http://localhost:8080
   JWT_SECRET=your_base64_encoded_256bit_secret_key_here
   ADMIN_EMAIL=admin@bank.com
   ADMIN_PASSWORD=your_secure_admin_password
   ```

---

### Step 2: Start Infrastructure & Applications

Start core infrastructure (Kafka, Zookeeper, Redis) and application containers:

```powershell
# 1. Start infrastructure containers
docker start zookeeper redis kafka

# 2. Build and start application services
docker compose -f docker-compose.apps.yml up --build -d

# 3. Verify all services are running
docker compose -f docker-compose.apps.yml ps
```

Expected output shows all 5 application services in state `Up`:
- `banking-system-api-gateway-1` (`:8080`)
- `banking-system-account-service-1` (`:8081`)
- `banking-system-transaction-service-1` (`:8082`)
- `banking-system-fraud-detection-service-1` (`:8084`)
- `banking-system-notification-service-1` (`:8085`)

---

### Step 3: Interactive Testing via Swagger UI

Open your browser to the aggregated Swagger documentation:
```text
http://localhost:8080/swagger-ui.html
```

1. Select **Account Service** or **Transaction Service** from the top-right definition dropdown.
2. Authenticate:
   - Call `POST /api/v1/auth/login` with your credentials or the default seeded admin (`admin@bank.com` / configured password).
   - Copy the `token` string from the response body.
   - Click the green **Authorize** (🔓) button at the top of Swagger.
   - Paste the token value and click **Authorize**.
3. All subsequent requests executed in Swagger will automatically send `Authorization: Bearer <token>`.


---

## Design Decisions & Trade-Offs

### 1. Hybrid SAGA Architecture
* **Decision**: Synchronous Feign client for initial balance deduction; asynchronous Kafka events for fraud evaluation, transaction completion, and notification delivery.
* **Trade-off**: Synchronous initial debit ensures the sender has sufficient balance before publishing events, avoiding wasteful messaging for failed requests. However, it couples the transaction service to account service availability during transfer initiation.

### 2. Gateway-Level Security vs Internal OAuth2 Resource Servers
* **Decision**: Single JWT validation boundary at Spring Cloud Gateway; identity passed via stripped/injected HTTP headers.
* **Trade-off**: Eliminates redundant cryptographic signature verification across internal microservice calls, improving latency and reducing configuration overhead. However, it requires that internal services be isolated on a private virtual network so external callers cannot bypass the gateway to forge headers.

### 3. Database-per-Service vs Shared Database
* **Decision**: Logical separation of databases (`account_db` and `transaction_db`) running on the same database engine instance.
* **Trade-off**: Preserves clean bounded contexts and independent schema evolution without incurring the infrastructure and maintenance cost of multiple running database clusters for local and staging environments.

### 4. Row-Level Pessimistic Locking on Verification
* **Decision**: `SELECT ... FOR UPDATE` during OTP submission instead of purely optimistic retry loops.
* **Trade-off**: Marginally reduces concurrent throughput on the specific transaction row being verified. However, it strictly eliminates the possibility of double credits or race conditions between automated timeout workers and user submissions.

---
