# iCollect: Step 1 Build Guide (from source code to container images)

Goal of this step: get the iCollect application building, tested and containerized on your own
machine, and pushed to GitHub. No AWS resources are created in this step, so it costs nothing.
Everything in later steps (Jenkins, ECR, EKS, Argo CD, monitoring) builds on exactly what you finish here.

## 1. What you are building

| Service | Language | Port | Purpose |
|---|---|---|---|
| customer-service | Java 17, Spring Boot 3.2 | 8081 | Customers and what they owe |
| payment-service | Java 17, Spring Boot 3.2 | 8082 | Takes payments, calls the other services |
| notification-service | Node.js 20, Express | 8083 | Sends and stores receipts |
| reporting-service | Python 3.12, FastAPI | 8084 | Read-only collection reports |

Connections: payment-service calls customer-service (validate and update balance) and
notification-service (send receipt). reporting-service reads from payment-service and customer-service.
See README.md for the diagram and error behaviour.

## 2. Install the tools

| Tool | Version | Check with |
|---|---|---|
| JDK | 17 (Temurin) | `java -version` |
| Maven | 3.9+ | `mvn -version` |
| Node.js | 20 LTS (18+ works) | `node -v` and `npm -v` |
| Python | 3.11 or 3.12 | `python3 --version` |
| Docker | Docker Desktop or Engine with Compose v2 | `docker version` and `docker compose version` |
| Git | any recent | `git --version` |
| Editor | VS Code or IntelliJ | |

Later steps will also need AWS CLI v2, Terraform, kubectl, Helm. You do not need them yet.

## 3. Get the code and put it in Git

```bash
unzip icollect-platform.zip && cd icollect-platform
git init -b main
git add . && git commit -m "iCollect: initial application code"
```

Create an empty repository on GitHub (for example `icollect-platform`, no README), then:

```bash
git remote add origin https://github.com/<your-user>/icollect-platform.git
git push -u origin main
```

Use one repository for now. Later, for the GitOps step, you will add a second repository
(`icollect-gitops`) that holds only deployment configuration.

## 4. Project layout

```
icollect-platform/
├── docker-compose.yml            run all four services together
├── demo.sh                       end-to-end walkthrough with curl
├── README.md                     architecture, endpoints, DevOps mapping
├── BUILD-GUIDE.md                this file
├── .gitignore
├── customer-service/             Java
│   ├── pom.xml                   dependencies and build (Maven)
│   ├── Dockerfile                multi-stage build, non-root user
│   └── src/
│       ├── main/java/com/example/customer/
│       │   ├── CustomerServiceApplication.java   entry point
│       │   ├── Customer.java                     data model (record)
│       │   ├── CustomerRepository.java           in-memory store, balance logic
│       │   └── CustomerController.java           REST endpoints + validation
│       ├── main/resources/application.yml        port, actuator, probes
│       └── test/java/.../CustomerRepositoryTest.java
├── payment-service/              Java
│   ├── pom.xml, Dockerfile
│   └── src/main/java/com/example/payment/
│       ├── PaymentController.java      REST endpoints
│       ├── PaymentService.java         the payment workflow (validate, update, record, notify)
│       ├── CustomerClient.java         HTTP client for customer-service (timeouts, 502 mapping)
│       ├── NotificationClient.java     HTTP client for notification-service (best effort)
│       ├── PaymentRepository.java      in-memory store
│       └── Payment / PaymentRequest / CustomerView   data models
├── notification-service/         Node.js
│   ├── package.json, package-lock.json, Dockerfile
│   ├── server.js                 Express app: /notifications and /health
│   └── test/server.test.js
└── reporting-service/            Python
    ├── requirements.txt, requirements-dev.txt, Dockerfile, pytest.ini
    ├── main.py                   FastAPI app: /reports/summary, /reports/customers/{id}
    └── tests/test_main.py
```

## 5. How the code works (what to be able to explain)

**customer-service.** `CustomerController` exposes `/customers`. `CustomerRepository` holds customers in a
`ConcurrentHashMap` (thread safe) and `applyPayment` rejects overpayment. Request bodies are validated with
Jakarta Validation annotations (`@NotBlank`, `@DecimalMin`). Actuator provides health and Prometheus endpoints.

**payment-service.** `PaymentService.makePayment` is the core workflow:
validate customer (404, 409, 400 on failure), update the balance, save the payment, increment a Micrometer
counter, then call notification (best effort). `CustomerClient` and `NotificationClient` use Spring's
`RestClient` with explicit connect and read timeouts. URLs come from environment variables, which is how
Kubernetes will configure them later.

**notification-service.** Express app that validates the body, stores a notification in memory, logs JSON,
and handles SIGTERM for graceful shutdown. `server.js` exports the app so tests can start it on a random port.

**reporting-service.** FastAPI app using `httpx` to fetch from the other services, with a 5 second timeout.
Upstream failure becomes HTTP 502, and a missing customer becomes 404.

**Why storage is in memory for now.** It keeps this step self-contained. A later step replaces it with
Spring Data JPA and Amazon RDS MySQL, with Flyway for schema migrations.

## 6. Build and test each service

Run these from the repository root.

### customer-service and payment-service (Maven)

```bash
cd customer-service
mvn -B clean verify          # compile, run unit tests, build the jar
ls target/*.jar              # expect customer-service-1.0.0.jar
cd ..

cd payment-service
mvn -B clean verify
ls target/*.jar              # expect payment-service-1.0.0.jar
cd ..
```
Expected: `BUILD SUCCESS` and `Tests run: ... Failures: 0, Errors: 0`. The first build downloads
dependencies and is slow; later builds are fast.

### notification-service (Node.js)

```bash
cd notification-service
npm ci                       # install exactly what package-lock.json says
npm test                     # expect: tests 3, pass 3, fail 0
cd ..
```

### reporting-service (Python)

```bash
cd reporting-service
python3 -m venv .venv && source .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements-dev.txt
pytest -q                    # expect: 3 passed
deactivate && cd ..
```

These four commands are exactly what the Jenkins "Build and test" stage will run in a later step.

## 7. Run it and test it

### Option A: Docker Compose (recommended)

```bash
docker compose up --build
```
In a second terminal:

```bash
./demo.sh
```
Expected highlights: customer 1001 owes 250.00; after paying 100.00 the balance is 150.00;
a receipt exists in notification-service; `/reports/summary` shows `totalCollected` 100.0 and
`paymentCount` 1; the overpayment returns HTTP 400. Stop with `Ctrl+C`, then `docker compose down`.

### Option B: run each service directly (useful for debugging)

```bash
(cd customer-service     && mvn spring-boot:run)        # terminal 1
(cd notification-service && npm start)                  # terminal 2
(cd payment-service      && mvn spring-boot:run)        # terminal 3
(cd reporting-service    && source .venv/bin/activate && uvicorn main:app --port 8084)   # terminal 4
```
Defaults point each service at `localhost`, so no environment variables are needed.

### Manual checks

```bash
curl localhost:8081/customers
curl -X POST localhost:8082/payments -H 'Content-Type: application/json' \
     -d '{"customerId":1001,"amount":100.00,"method":"CARD"}'
curl localhost:8083/notifications
curl localhost:8084/reports/summary
curl localhost:8082/actuator/health/readiness
curl localhost:8082/actuator/prometheus | grep icollect_payments_total
```

Failure experiments worth doing (they are interview stories):
1. Stop `notification-service`, make a payment: it still succeeds and payment-service logs a warning.
2. Stop `customer-service`, make a payment: you get HTTP 502.
3. Stop `payment-service`, call `/reports/summary`: you get HTTP 502.

## 8. Build and inspect the container images

Compose already built them. To build one by hand and inspect it:

```bash
docker build -t payment-service:local ./payment-service
docker images | grep -E "payment|customer|notification|reporting"
docker run --rm -p 8082:8082 -e CUSTOMER_SERVICE_URL=http://host.docker.internal:8081 payment-service:local
docker run --rm --entrypoint id payment-service:local        # confirms it is not running as root
```

What to notice (and say in an interview):
- Multi-stage Dockerfiles: Maven and the JDK are only in the build stage, the runtime image is JRE-only.
- Non-root user in every image.
- Dependency layer is cached before source is copied, so rebuilds are fast.
- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70` makes the JVM respect container memory limits.
- No database, credentials or environment-specific values are inside any image.

Optional: scan an image locally with Trivy.
```bash
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy image --severity HIGH,CRITICAL payment-service:local
```

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `release version 17 not supported` | Wrong JDK | Set JAVA_HOME to JDK 17 |
| Port already in use | Another process on 8081-8084 | Stop it, or change the port mapping |
| Payment returns 502 | customer-service not reachable | Check it is running; in Compose check the service name in `CUSTOMER_SERVICE_URL` |
| `npm ci` fails | Missing or edited lock file | Run `npm install` once to regenerate package-lock.json |
| `ModuleNotFoundError` in Python | Virtualenv not active | `source .venv/bin/activate` |
| Docker build is very slow | No layer cache | Keep `pom.xml` copy before `src` copy (already done) |
| Java test or compile error | The Java code was not compiled by its author | Copy the exact error and ask for a fix |

## 10. Before you touch AWS: understand the costs

Do this before Step 2 (verified against current AWS Free Tier information at the time of writing,
so recheck the billing console for your own account):
- New accounts choose a Free plan or Paid plan. The Free plan gives $100 in credits, up to $200
  with onboarding activities. It ends after 6 months or when credits run out, whichever is first,
  and the account is then closed (you get a grace period to upgrade).
- The old 12-month free tier is no longer what new accounts get, so do not assume EC2 or RDS hours are free.
- Amazon EKS charges for the control plane by the hour, and NAT gateways, load balancers and
  EC2 nodes also bill by the hour. A cluster left running overnight uses credits.
- First actions in the AWS account: enable MFA on the root user, create an admin IAM Identity Center
  user (do not work as root), and create a monthly budget with email alerts in AWS Budgets.
- Working rule for the whole project: build with Terraform, and destroy with Terraform at the end of every session.

## 11. Definition of done for Step 1

- [ ] `mvn -B clean verify` passes in customer-service and payment-service
- [ ] `npm test` passes in notification-service
- [ ] `pytest` passes in reporting-service
- [ ] `docker compose up --build` starts all four services and `./demo.sh` runs cleanly
- [ ] You tried the three failure experiments and can explain each result
- [ ] Code is pushed to GitHub with a clean `git status`
- [ ] You can draw the service diagram and explain one payment end to end

## 12. What comes next

Step 2: AWS account setup, guardrails and Terraform foundation (VPC, ECR). Step 3: Jenkins pipeline that
runs exactly the commands in section 6 and pushes images to ECR. Step 4: EKS with Terraform. Step 5: Helm chart
and Argo CD. Step 6: RDS and Flyway. Step 7: Prometheus, Grafana and alerts.
