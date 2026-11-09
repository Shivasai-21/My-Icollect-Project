#!/usr/bin/env bash
# End-to-end walkthrough. Run after `docker compose up --build`.
set -euo pipefail
pp() { python3 -m json.tool; }

echo "== 1. Customers (Customer service, Java) =="
curl -s localhost:8081/customers | pp

echo "== 2. Pay 100.00 for customer 1001 (Payment service, Java -> Customer -> Notification) =="
curl -s -X POST localhost:8082/payments -H 'Content-Type: application/json' \
  -d '{"customerId":1001,"amount":100.00,"method":"CARD"}' | pp

echo "== 3. Customer balance dropped (Customer service) =="
curl -s localhost:8081/customers/1001 | pp

echo "== 4. Receipt was sent (Notification service, Node.js) =="
curl -s "localhost:8083/notifications?customerId=1001" | pp

echo "== 5. Collection summary (Reporting service, Python) =="
curl -s localhost:8084/reports/summary | pp

echo "== 6. Overpayment is rejected (expect 400) =="
curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST localhost:8082/payments \
  -H 'Content-Type: application/json' -d '{"customerId":1003,"amount":50.00,"method":"UPI"}'
