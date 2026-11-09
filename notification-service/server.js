'use strict';

const express = require('express');
const crypto = require('crypto');

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 8083;

// In-memory store for the demo. In production: persist in a table or DynamoDB,
// and consume events from SQS instead of receiving HTTP calls.
const notifications = [];

function log(level, msg, extra = {}) {
  console.log(JSON.stringify({ time: new Date().toISOString(), level, service: 'notification-service', msg, ...extra }));
}

// Liveness / readiness endpoint used by Kubernetes probes and the ALB health check
app.get('/health', (req, res) => {
  res.json({ status: 'UP', service: 'notification-service' });
});

// Called by the Java Payment service after a successful payment
app.post('/notifications', (req, res) => {
  const { type, customerId, email, message, paymentId, amount } = req.body || {};

  if (!type || customerId === undefined || !email || !message) {
    return res.status(400).json({ error: 'type, customerId, email and message are required' });
  }

  const notification = {
    id: crypto.randomUUID(),
    type,
    customerId,
    email,
    message,
    paymentId: paymentId || null,
    amount: amount === undefined ? null : amount,
    channel: 'EMAIL',
    status: 'SENT', // a real implementation would call Amazon SES here
    createdAt: new Date().toISOString(),
  };

  notifications.push(notification);
  log('info', 'notification sent', { to: email, type, notificationId: notification.id });
  res.status(201).json(notification);
});

// Lets support teams (or the reporting service) see what was sent
app.get('/notifications', (req, res) => {
  const { customerId } = req.query;
  const result = customerId
    ? notifications.filter((n) => String(n.customerId) === String(customerId))
    : notifications;
  res.json(result);
});

// Start the server only when run directly (so tests can import the app)
if (require.main === module) {
  const server = app.listen(PORT, () => log('info', `listening on port ${PORT}`));

  // Graceful shutdown so rolling updates in Kubernetes do not drop requests
  process.on('SIGTERM', () => {
    log('info', 'SIGTERM received, shutting down');
    server.close(() => process.exit(0));
  });
}

module.exports = app;
