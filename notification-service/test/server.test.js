'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const app = require('../server');

let server;
let base;

before(
  () =>
    new Promise((resolve) => {
      server = app.listen(0, () => {
        base = `http://127.0.0.1:${server.address().port}`;
        resolve();
      });
    })
);

after(() => new Promise((resolve) => server.close(resolve)));

test('health endpoint reports UP', async () => {
  const res = await fetch(`${base}/health`);
  assert.strictEqual(res.status, 200);
  assert.strictEqual((await res.json()).status, 'UP');
});

test('rejects an incomplete notification with 400', async () => {
  const res = await fetch(`${base}/notifications`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'PAYMENT_RECEIVED' }),
  });
  assert.strictEqual(res.status, 400);
});

test('stores a notification and returns it by customer', async () => {
  const create = await fetch(`${base}/notifications`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      type: 'PAYMENT_RECEIVED',
      customerId: 1001,
      email: 'asha.rao@example.com',
      message: 'Payment received',
      amount: 100,
    }),
  });
  assert.strictEqual(create.status, 201);

  const list = await fetch(`${base}/notifications?customerId=1001`);
  const body = await list.json();
  assert.strictEqual(body.length, 1);
  assert.strictEqual(body[0].status, 'SENT');
});
