// Vanilla JS for fetch calls. Every non-GET request carries the CSRF token from the meta tags (REQ-SE-001 3).
'use strict';

const Shop = (() => {
    function csrfHeaders() {
        const token = document.querySelector('meta[name="_csrf"]');
        const header = document.querySelector('meta[name="_csrf_header"]');
        return token && header ? { [header.content]: token.content } : {};
    }

    async function api(method, url, body, extraHeaders) {
        const headers = { Accept: 'application/json', ...csrfHeaders(), ...extraHeaders };
        if (body !== undefined) {
            headers['Content-Type'] = 'application/json';
        }
        const response = await fetch(url, {
            method,
            headers,
            credentials: 'same-origin',
            body: body === undefined ? undefined : JSON.stringify(body),
        });
        if (response.status === 401) {
            window.location.href = '/login';
            throw new Error('로그인이 필요합니다.');
        }
        if (!response.ok) {
            const error = await response.json().catch(() => ({ message: '요청을 처리하지 못했습니다.' }));
            throw new Error(error.message);
        }
        return response.status === 204 ? null : response.json();
    }

    function showMessage(text, isError) {
        const message = document.getElementById('message');
        if (message) {
            message.textContent = text;
            message.classList.toggle('error', Boolean(isError));
        }
    }

    const sleep = (millis) => new Promise((resolve) => setTimeout(resolve, millis));

    async function orderStatus(orderId) {
        return api('GET', `/api/orders/${orderId}/status`);
    }

    return { api, showMessage, sleep, orderStatus };
})();

// REQ-F-008: mock payment screen - countdown, "결제 준비 중" until the READY payment exists, then result polling.
const MockPayment = (() => {
    const POLL_INTERVAL_MILLIS = 1000;
    const RESULT_POLL_LIMIT = 30;

    function start(page) {
        const orderId = page.dataset.orderId;
        const expiresAt = Number(page.dataset.expiresAt);
        const buttons = [...page.querySelectorAll('[data-approve]')];
        const countdown = page.querySelector('[data-countdown]');
        let payable = page.dataset.payable === 'true';
        let ready = page.dataset.paymentStatus === 'READY';
        let busy = false;

        const refreshButtons = () => buttons.forEach((button) => {
            button.disabled = busy || !payable || !ready || Date.now() >= expiresAt;
        });

        const tick = () => {
            const left = Math.max(0, expiresAt - Date.now());
            const minutes = String(Math.floor(left / 60000)).padStart(2, '0');
            const seconds = String(Math.floor((left % 60000) / 1000)).padStart(2, '0');
            countdown.textContent = `${minutes}:${seconds}`;
            if (left === 0 && !busy) {
                Shop.showMessage('결제 가능 시간이 지났습니다.', true);
            }
            refreshButtons();
        };
        tick();
        setInterval(tick, POLL_INTERVAL_MILLIS);

        if (!ready && payable) {
            waitUntilReady(orderId).then((status) => {
                ready = status.paymentStatus === 'READY';
                payable = status.status === 'PAYMENT_PENDING';
                Shop.showMessage(ready ? '' : `결제할 수 없는 주문입니다. (${status.status})`, !ready);
                refreshButtons();
            });
        }

        buttons.forEach((button) => button.addEventListener('click', async () => {
            busy = true;
            refreshButtons();
            Shop.showMessage('결제 처리 중...');
            try {
                const method = page.querySelector('input[name="method"]:checked').value;
                await Shop.api('POST', '/mock-pg/approve',
                    { orderId: Number(orderId), result: button.dataset.approve, method },
                    { 'Idempotency-Key': orderId });
                await waitForResult(orderId);
            } catch (error) {
                busy = false;
                Shop.showMessage(error.message, true);
                refreshButtons();
            }
        }));
    }

    async function waitUntilReady(orderId) {
        Shop.showMessage('결제 준비 중...');
        for (;;) {
            const status = await Shop.orderStatus(orderId);
            if (status.paymentStatus !== null || status.status !== 'PAYMENT_PENDING') {
                return status;
            }
            await Shop.sleep(POLL_INTERVAL_MILLIS);
        }
    }

    /** Polls every second for up to 30 seconds, then moves to the order detail (the result screen). */
    async function waitForResult(orderId) {
        for (let i = 0; i < RESULT_POLL_LIMIT; i++) {
            const status = await Shop.orderStatus(orderId);
            if (status.status !== 'PAYMENT_PENDING') {
                window.location.href = `/orders/${orderId}`;
                return;
            }
            await Shop.sleep(POLL_INTERVAL_MILLIS);
        }
        Shop.showMessage('결과 확인이 늦어지고 있습니다. 주문 상세에서 상태를 확인해 주세요.', true);
    }

    return { start };
})();

document.addEventListener('DOMContentLoaded', () => {
    const run = (action) => action().catch((error) => Shop.showMessage(error.message, true));

    const addButton = document.querySelector('[data-cart-add]');
    if (addButton) {
        addButton.addEventListener('click', () => run(async () => {
            const productId = Number(document.querySelector('.product-detail').dataset.productId);
            const qty = Number(document.getElementById('quantity').value);
            await Shop.api('POST', '/api/cart/items', { productId, qty });
            Shop.showMessage('장바구니에 담았습니다.');
        }));
    }

    const buyNowButton = document.querySelector('[data-buy-now]');
    if (buyNowButton) {
        buyNowButton.addEventListener('click', () => {
            const productId = document.querySelector('.product-detail').dataset.productId;
            const qty = document.getElementById('quantity').value;
            window.location.href = `/orders/checkout?productId=${productId}&qty=${qty}`;
        });
    }

    const checkout = document.querySelector('[data-checkout]');
    const placeOrderButton = document.querySelector('[data-place-order]');
    if (checkout && placeOrderButton) {
        // REQ-F-006 / REQ-SE-002: no prices are sent, and the key issued with this page makes a double click harmless
        placeOrderButton.addEventListener('click', () => run(async () => {
            const form = document.getElementById('shipping-form');
            if (!form.reportValidity()) {
                return;
            }
            const items = [...checkout.querySelectorAll('tr[data-product-id]')].map((row) => ({
                productId: Number(row.dataset.productId),
                qty: Number(row.dataset.qty),
            }));
            const address = {
                receiverName: form.elements.receiverName.value,
                receiverPhone: form.elements.receiverPhone.value,
                line: form.elements.line.value,
            };
            placeOrderButton.disabled = true;
            try {
                const result = await Shop.api('POST', '/api/orders',
                    { items, address, fromCart: checkout.dataset.fromCart === 'true' },
                    { 'Idempotency-Key': checkout.dataset.idempotencyKey });
                window.location.href = result.paymentUrl;
            } finally {
                placeOrderButton.disabled = false;
            }
        }));
    }

    const productIdOf = (element) => element.closest('tr').dataset.productId;

    document.querySelectorAll('[data-cart-qty]').forEach((select) => {
        select.addEventListener('change', () => run(async () => {
            await Shop.api('PATCH', `/api/cart/items/${productIdOf(select)}`, { qty: Number(select.value) });
            window.location.reload();
        }));
    });

    document.querySelectorAll('[data-cart-remove]').forEach((button) => {
        button.addEventListener('click', () => run(async () => {
            await Shop.api('DELETE', `/api/cart/items/${productIdOf(button)}`);
            window.location.reload();
        }));
    });

    const clearButton = document.querySelector('[data-cart-clear]');
    if (clearButton) {
        clearButton.addEventListener('click', () => run(async () => {
            await Shop.api('DELETE', '/api/cart');
            window.location.reload();
        }));
    }

    const paymentPage = document.querySelector('[data-payment]');
    if (paymentPage) {
        MockPayment.start(paymentPage);
    }

    const cancelButton = document.querySelector('[data-order-cancel]');
    if (cancelButton) {
        cancelButton.addEventListener('click', () => run(async () => {
            if (!window.confirm('주문을 취소할까요? 결제가 끝난 주문은 결제도 함께 취소됩니다.')) {
                return;
            }
            const orderId = document.querySelector('[data-order-detail]').dataset.orderId;
            cancelButton.disabled = true;
            await Shop.api('POST', `/api/orders/${orderId}/cancel`);
            window.location.reload();
        }));
    }

    document.querySelectorAll('[data-dlt-retry]').forEach((button) => {
        button.addEventListener('click', () => run(async () => {
            const id = button.closest('tr').dataset.deadLetterId;
            button.disabled = true;
            await Shop.api('POST', `/api/admin/dlt/${id}/retry`);
            Shop.showMessage(`DLT ${id} 을(를) 원 토픽으로 다시 보냈습니다.`);
        }));
    });

    document.querySelectorAll('[data-outbox-requeue]').forEach((button) => {
        button.addEventListener('click', () => run(async () => {
            const id = button.closest('tr').dataset.outboxId;
            button.disabled = true;
            await Shop.api('POST', `/api/admin/outbox/${id}/requeue`);
            Shop.showMessage(`아웃박스 이벤트 ${id} 을(를) 발행 대기(PENDING)로 되돌렸습니다.`);
        }));
    });

    const failureSwitchButton = document.querySelector('[data-failure-switch]');
    if (failureSwitchButton) {
        failureSwitchButton.addEventListener('click', () => run(async () => {
            const form = document.getElementById('failure-switch');
            const remaining = await Shop.api('POST', '/api/admin/failure-switch', {
                consumerGroup: form.elements.consumerGroup.value,
                count: Number(form.elements.count.value),
            });
            document.querySelector('[data-remaining-failures]').textContent = JSON.stringify(remaining);
            Shop.showMessage('장애 시연 스위치를 적용했습니다.');
        }));
    }

    const resyncButton = document.querySelector('[data-stock-resync]');
    if (resyncButton) {
        resyncButton.addEventListener('click', () => run(async () => {
            const result = await Shop.api('POST', '/api/admin/stock/resync');
            Shop.showMessage(`상품 ${result.synchronizedProducts}개의 Redis 재고를 다시 맞췄습니다.`);
        }));
    }
});
