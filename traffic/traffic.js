import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY', 'CHF', 'AUD', 'CAD', 'SGD', 'INR', 'HKD'];
const STATUSES = ['SETTLED', 'PENDING', 'FAILED'];

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: 6,
      timeUnit: '1s',
      duration: '24h',
      preAllocatedVUs: 15,
      maxVUs: 40,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.20'],
  },
};

// recent payment ids seen by this VU, for GET-by-id traffic
const seenIds = [];

function rand(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

export default function () {
  const roll = Math.random();

  if (roll < 0.6) {
    // create a payment
    const payload = JSON.stringify({
      accountId: Math.floor(Math.random() * 500) + 1,
      amount: Math.round(Math.random() * 499900 + 100) / 100,
      currency: rand(CURRENCIES),
    });
    const res = http.post(`${BASE}/api/payments`, payload, {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'POST /api/payments' },
    });
    check(res, { 'payment created': (r) => r.status === 201 || r.status === 200 });
    if (res.status === 201 || res.status === 200) {
      try {
        const id = res.json('paymentId');
        if (id) {
          seenIds.push(id);
          if (seenIds.length > 50) seenIds.shift();
        }
      } catch (_) { /* ignore parse errors */ }
    }
  } else if (roll < 0.8) {
    // search: the query that goes slow without its index
    const from = new Date(Date.now() - 24 * 3600 * 1000).toISOString().slice(0, 19);
    const res = http.get(
      `${BASE}/api/payments?status=${rand(STATUSES)}&from=${from}`,
      { tags: { name: 'GET /api/payments (search)' } },
    );
    check(res, { 'search ok': (r) => r.status === 200 });
  } else if (roll < 0.9 && seenIds.length > 0) {
    const res = http.get(`${BASE}/api/payments/${rand(seenIds)}`, {
      tags: { name: 'GET /api/payments/{id}' },
    });
    check(res, { 'fetch ok': (r) => r.status === 200 });
  } else {
    const accountId = Math.floor(Math.random() * 500) + 1;
    const res = http.get(`${BASE}/api/accounts/${accountId}/balance`, {
      tags: { name: 'GET /api/accounts/{id}/balance' },
    });
    check(res, { 'balance ok': (r) => r.status === 200 });
  }

  sleep(0.1);
}
