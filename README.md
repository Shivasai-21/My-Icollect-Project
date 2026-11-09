# iCollect Platform: Payments and Collections (Java + Node.js + Python)

A small, runnable microservice application that mirrors the **iCollect** domain
(Customer, Account/Payment, Notification) plus a read-only reporting service.
It is built to be deployed the way the rest of this project describes:
Jenkins -> ECR -> GitOps repo -> Argo CD -> EKS.

## What the application does

Internal operations and finance teams collect money that customers owe.
A customer has an outstanding balance. When a payment is taken, the balance must go down,
the payment must be recorded, the customer must get a receipt, and finance must be able to
see how much has been collected.

## The services

| Service | Language | Port | Use |
|---|---|---|---|
| customer-service | Java 17, Spring Boot 3 | 8081 | Owns customers and their outstanding balance (`amountDue`) |
| payment-service | Java 17, Spring Boot 3 | 8082 | Takes payments; the orchestrator that calls the other services |
| notification-service | Node.js 20, Express | 8083 | Sends and stores receipts (email is simulated) |
| reporting-service | Python 3.12, FastAPI | 8084 | Read-only collection reports built from the other services |
| web-dashboard | nginx + HTML/JS | 8080 | Web page for making payments and watching the chain of calls; reverse-proxies `/api/*` to the four services |

## How they connect

```
                         POST /payments
 Operations user  ------------------------->  payment-service (Java)
                                                 |      |
        1. GET /customers/{id}  (validate)       |      |  3. POST /notifications (best effort)
        2. POST /customers/{id}/apply-payment    |      |
                                                 v      v
                                   customer-service   notification-service
                                        (Java)            (Node.js)
                                           ^
                                           |  GET /customers, GET /customers/{id}
 Finance user  ---->  reporting-service (Python)
                                           |  GET /payments, GET /payments?customerId=
                                           v
                                      payment-service
```

### Flow of one payment (POST /payments)

1. **payment-service** asks **customer-service** whether the customer exists and is ACTIVE,
   and checks that the amount does not exceed what is owed.
2. payment-service tells customer-service to reduce the balance (`apply-payment`),
   then records the payment as SUCCESS.
3. payment-service calls **notification-service** to send a receipt. This call is best effort:
   if it fails, the payment is still valid and a warning is logged.
4. **reporting-service** later reads payments and customers to produce totals and per-customer history.

### Error behaviour (worth explaining in an interview)

| Situation | Result |
|---|---|
| Unknown customer | 404 from payment-service |
| Customer not ACTIVE | 409 |
| Amount above amount due, or invalid body | 400 |
| Customer service down or slow (timeouts 2s connect, 3s read) | 502 from payment-service |
| Notification service down | Payment still succeeds, warning logged |
| Payment or customer service down | reporting-service returns 502 |

## Folders

| Folder | What it is |
|---|---|
| `customer-service`, `payment-service`, `notification-service`, `reporting-service`, `web-dashboard` | The application services |
| `jenkins/` | Shared Jenkins pipeline, ECR scripts, GitOps update script (see `jenkins/README.md`) |
| `gitops/` | Helm chart, per-service values, Argo CD manifests. Copy into its own `icollect-gitops` repository |
| `infra/` | Terraform for VPC and EKS, plus lab-up, lab-down and install-argocd scripts |
| `BUILD-GUIDE.md`, `GITOPS-EKS-GUIDE.md` | Step-by-step guides for building locally and deploying to EKS |

## Run it

```bash
docker compose up --build
./demo.sh            # walks through customers, a payment, the receipt and the report
```

Or run services individually:

```bash
# Java (needs JDK 17 and Maven)
cd customer-service && mvn spring-boot:run
cd payment-service  && mvn spring-boot:run

# Node.js
cd notification-service && npm install && npm start

# Python
cd reporting-service && pip install -r requirements.txt && uvicorn main:app --port 8084
```

Seed customers: 1001 (owes 250.00), 1002 (owes 1200.50), 1003 (owes 0.00).

## Endpoints

| Service | Endpoint |
|---|---|
| customer | `GET /customers`, `GET /customers/{id}`, `POST /customers`, `POST /customers/{id}/apply-payment` |
| payment | `POST /payments`, `GET /payments?customerId=`, `GET /payments/{id}` |
| notification | `POST /notifications`, `GET /notifications?customerId=`, `GET /health` |
| reporting | `GET /reports/summary`, `GET /reports/customers/{id}`, `GET /health` |
| Java services | `GET /actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus` |

## How this maps to the DevOps setup

| Concern | In this project | In production (EKS) |
|---|---|---|
| Build | `mvn verify` for Java, `npm ci` for Node, `pip install` for Python | Same stages in the shared Jenkins pipeline; the build stage differs per language |
| Image | One multi-stage Dockerfile per service, non-root user | SHA tag pushed to ECR, scanned by Trivy |
| Config | Environment variables (`CUSTOMER_SERVICE_URL` etc.) | Helm values -> ConfigMap, pointing to Kubernetes Service DNS names |
| Probes | `/actuator/health/*` (Java), `/health` (Node, Python) | liveness, readiness and startup probes in the Helm chart |
| Metrics | Micrometer `/actuator/prometheus`, custom counter `icollect_payments_total` | Scraped by Prometheus, shown in Grafana |
| Storage | In-memory, so data resets on restart | RDS MySQL (one schema per domain), reporting reads the replica |
| Notification | Direct HTTP call | SNS/SQS event consumed by the notification service |
| Shutdown | Node handles SIGTERM; Spring Boot supports graceful shutdown | Zero-downtime rolling updates |

Namespaces: customer-service and payment-service in `icollect`, notification-service in `shared`,
reporting-service in `rmicw`. Cross-namespace calls use the full Service DNS name and need
NetworkPolicy allow rules.

## Tests

- `customer-service` and `payment-service`: JUnit tests (`mvn test`). Payment tests use Mockito to isolate the downstream clients.
- `notification-service`: `npm test` (Node built-in test runner).
- `reporting-service`: `pytest`.
- The Node and Python tests were run during development. The Java tests were written but not compiled in the authoring environment, so run `mvn verify` first.

## Talking points

- Why separate services: independent deploys and scaling, and each can use the best language for its job.
- Why the notification call is best effort: a receipt failure must not undo a recorded payment.
- What breaks as it scales: HPA adds payment pods, and each pod calls customer-service, so downstream
  capacity and (with a real database) connection pools must be sized together.
- What you would change for production: events instead of HTTP for notifications, idempotency keys
  on `POST /payments`, retries with backoff and circuit breakers, and a real database.
