"""Reporting service (Python / FastAPI).

Read-only service that combines data from the Payment and Customer services
into collection reports. In production it would read from the RDS read replica.
"""
from typing import Optional, Dict
import os
from collections import defaultdict
from decimal import Decimal

import httpx
from fastapi import FastAPI, HTTPException

PAYMENT_SERVICE_URL = os.getenv("PAYMENT_SERVICE_URL", "http://localhost:8082")
CUSTOMER_SERVICE_URL = os.getenv("CUSTOMER_SERVICE_URL", "http://localhost:8081")

app = FastAPI(title="reporting-service")


async def fetch_json(client: httpx.AsyncClient, url: str, params: Optional[Dict] = None):
    """GET a URL and return JSON. Upstream failures become 502, a missing resource stays 404."""
    try:
        response = await client.get(url, params=params, timeout=5.0)
        if response.status_code == 404:
            raise HTTPException(status_code=404, detail="Not found")
        response.raise_for_status()
        return response.json()
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Upstream call failed: {url} ({exc.__class__.__name__})")


@app.get("/health")
async def health():
    return {"status": "UP", "service": "reporting-service"}


@app.get("/reports/summary")
async def summary():
    """Total collected, collected by payment method, and what is still outstanding."""
    async with httpx.AsyncClient() as client:
        payments = await fetch_json(client, f"{PAYMENT_SERVICE_URL}/payments")
        customers = await fetch_json(client, f"{CUSTOMER_SERVICE_URL}/customers")

    successful = [p for p in payments if p["status"] == "SUCCESS"]
    total_collected = sum((Decimal(str(p["amount"])) for p in successful), Decimal("0"))
    by_method: Dict[str, Decimal] = defaultdict(Decimal)
    for p in successful:
        by_method[p["method"]] += Decimal(str(p["amount"]))
    outstanding = sum((Decimal(str(c["amountDue"])) for c in customers), Decimal("0"))

    return {
        "paymentCount": len(successful),
        "totalCollected": float(total_collected),
        "collectedByMethod": {m: float(v) for m, v in by_method.items()},
        "totalOutstanding": float(outstanding),
        "customerCount": len(customers),
    }


@app.get("/reports/customers/{customer_id}")
async def customer_report(customer_id: int):
    """One customer's balance together with their payment history."""
    async with httpx.AsyncClient() as client:
        customer = await fetch_json(client, f"{CUSTOMER_SERVICE_URL}/customers/{customer_id}")
        payments = await fetch_json(client, f"{PAYMENT_SERVICE_URL}/payments", params={"customerId": customer_id})

    paid = sum((Decimal(str(p["amount"])) for p in payments if p["status"] == "SUCCESS"), Decimal("0"))
    return {
        "customer": customer,
        "totalPaid": float(paid),
        "paymentCount": len(payments),
        "payments": payments,
    }
