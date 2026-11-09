import main
from fastapi.testclient import TestClient

client = TestClient(main.app)

PAYMENTS = [
    {"id": "p1", "customerId": 1001, "amount": 100.0, "method": "CARD", "status": "SUCCESS"},
    {"id": "p2", "customerId": 1002, "amount": 50.5, "method": "UPI", "status": "SUCCESS"},
    {"id": "p3", "customerId": 1002, "amount": 20.0, "method": "CARD", "status": "FAILED"},
]
CUSTOMERS = [
    {"id": 1001, "name": "Asha Rao", "amountDue": 150.0, "status": "ACTIVE"},
    {"id": 1002, "name": "Ravi Kumar", "amountDue": 1150.0, "status": "ACTIVE"},
]


def fake_fetch(monkeypatch):
    async def _fetch(client_, url, params=None):
        return PAYMENTS if url.endswith("/payments") else CUSTOMERS

    monkeypatch.setattr(main, "fetch_json", _fetch)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"


def test_summary_counts_only_successful_payments(monkeypatch):
    fake_fetch(monkeypatch)
    body = client.get("/reports/summary").json()
    assert body["paymentCount"] == 2
    assert body["totalCollected"] == 150.5
    assert body["collectedByMethod"] == {"CARD": 100.0, "UPI": 50.5}
    assert body["totalOutstanding"] == 1300.0


def test_summary_returns_502_when_upstream_is_down():
    # No services are running in the test environment, so the real fetch fails
    response = client.get("/reports/summary")
    assert response.status_code == 502
