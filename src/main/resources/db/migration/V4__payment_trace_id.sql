-- REQ-NF-003: the mock payment approval is a separate HTTP request with its own traceId. The payment keeps the traceId
-- of the order.created event that prepared it, so payment.completed / payment.failed continue the order's trace and one
-- traceId leads from the order request to PAID in every module's log.
ALTER TABLE payments
    ADD COLUMN trace_id VARCHAR(64) NULL AFTER method;
