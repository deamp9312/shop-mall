// REQ-NF-001 3): 100 concurrent users order one product until it sells out. Records TPS, p95 of the order API and
// the oversold count. Run against the app started as in README "실행" (local profile, seed data):
//
//   docker run --rm -v "$PWD/load-test:/scripts" -e BASE_URL=http://host.docker.internal:8080 \
//       grafana/k6:0.54.0 run /scripts/order-concurrency.js
//
// Options (environment variables): BASE_URL, PRODUCT_ID (default 1), STOCK (DB stock set before the run, default 500),
// VUS (default 100), ITERATIONS (orders per user, default 10), ADMIN_EMAIL / ADMIN_PASSWORD, USER_PASSWORD.
// Accounts are the seed accounts (README "시드 계정"); users take turns between user1 and user2, one session per VU.
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const STOCK = Number(__ENV.STOCK || 500);
const VUS = Number(__ENV.VUS || 100);
const ITERATIONS = Number(__ENV.ITERATIONS || 10);
const ADMIN = { email: __ENV.ADMIN_EMAIL || 'admin@shop.local', password: __ENV.ADMIN_PASSWORD || 'Admin1234!' };
const USERS = ['user1@shop.local', 'user2@shop.local'];
const USER_PASSWORD = __ENV.USER_PASSWORD || 'User1234!';

const accepted = new Counter('orders_accepted');
const outOfStock = new Counter('orders_out_of_stock');
const failed = new Counter('orders_failed');
const orderDuration = new Trend('order_duration', true);

export const options = {
    scenarios: {
        orders: { executor: 'per-vu-iterations', vus: VUS, iterations: ITERATIONS, maxDuration: '5m' },
    },
    // p95 300 ms is a reference target for a local PC (REQ-NF-001 constraints); unexpected errors must stay at zero.
    thresholds: {
        order_duration: ['p(95)<300'],
        orders_failed: ['count==0'],
    },
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
    // k6 empties the cookie jar every iteration by default; a user keeps one logged-in session (SESSION cookie).
    noCookiesReset: true,
};

// Per VU: the CSRF token of its session (REQ-SE-001 3) - fetch requests send it as a header, forms as _csrf.
let csrf = null;

export function setup() {
    login(ADMIN.email, ADMIN.password);
    const form = http.get(`${BASE_URL}/admin/products/${PRODUCT_ID}/edit`);
    const token = csrfOf(form);
    const adjusted = http.post(`${BASE_URL}/admin/products/${PRODUCT_ID}/stock`,
        { newStock: String(STOCK), _csrf: token.value });
    check(adjusted, { 'stock adjusted': (r) => r.status === 200 });
    const before = availableStock();
    console.log(`available stock before the run: ${before}`);
    return { availableBefore: before };
}

export default function () {
    if (csrf === null) {
        login(USERS[(exec.vu.idInTest - 1) % USERS.length], USER_PASSWORD);
        csrf = csrfOf(http.get(`${BASE_URL}/orders/checkout?productId=${PRODUCT_ID}&qty=1`));
    }
    const response = http.post(`${BASE_URL}/api/orders`, JSON.stringify({
        items: [{ productId: PRODUCT_ID, qty: 1 }],
        address: { receiverName: '부하테스트', receiverPhone: '010-0000-0000', line: '부하 테스트 주소' },
        fromCart: false,
    }), {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': uuid(),
            [csrf.header]: csrf.value,
        },
        tags: { name: 'place_order' },
    });
    orderDuration.add(response.timings.duration);
    if (response.status === 200) {
        accepted.add(1);
    } else if (response.status === 409 && response.body.includes('OUT_OF_STOCK')) {
        outOfStock.add(1);
    } else {
        failed.add(1);
        console.warn(`unexpected order response ${response.status}: ${response.body}`);
    }
}

export function teardown(data) {
    const after = availableStock();
    const soldByServer = data.availableBefore - after;
    console.log(`available stock after the run: ${after} (sold according to Redis: ${soldByServer})`);
    check(after, { 'available stock never below zero': (value) => value >= 0 });
}

export function handleSummary(data) {
    const count = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
    if (!data.setup_data) {
        return { stdout: '\nsetup failed, no result (see the errors above)\n' };
    }
    const before = data.setup_data.availableBefore;
    const sold = count('orders_accepted');
    const durationSeconds = data.state.testRunDurationMs / 1000;
    const result = {
        users: VUS,
        orderRequests: sold + count('orders_out_of_stock') + count('orders_failed'),
        accepted: sold,
        outOfStock: count('orders_out_of_stock'),
        failed: count('orders_failed'),
        availableBefore: before,
        oversold: Math.max(0, sold - before),
        tps: Math.round(((sold + count('orders_out_of_stock') + count('orders_failed')) / durationSeconds) * 10) / 10,
        p95Ms: Math.round(data.metrics.order_duration.values['p(95)']),
        medianMs: Math.round(data.metrics.order_duration.values.med),
        maxMs: Math.round(data.metrics.order_duration.values.max),
        runSeconds: Math.round(durationSeconds * 10) / 10,
    };
    return {
        stdout: `\nREQ-NF-001 load test result\n${JSON.stringify(result, null, 2)}\n`,
        '/scripts/result.json': JSON.stringify(result, null, 2),
    };
}

function login(email, password) {
    const page = http.get(`${BASE_URL}/login`);
    const token = csrfOf(page);
    const response = http.post(`${BASE_URL}/login`, { email, password, _csrf: token.value });
    if (response.url.includes('/login')) {
        fail(`login failed for ${email}`);
    }
}

// Logged-in pages carry the token in <meta> (for fetch); the login page only in the form's hidden _csrf input.
function csrfOf(page) {
    const doc = page.html();
    const value = doc.find('meta[name="_csrf"]').attr('content') || doc.find('input[name="_csrf"]').attr('value');
    const header = doc.find('meta[name="_csrf_header"]').attr('content') || 'X-CSRF-TOKEN';
    if (!value) {
        fail(`no CSRF token on ${page.url} (status ${page.status})`);
    }
    return { value, header };
}

function availableStock() {
    const text = http.get(`${BASE_URL}/products/${PRODUCT_ID}`).html().find('#available-stock').text();
    return Number(text.trim());
}

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}
