package com.platform.payment.service;

import com.platform.payment.dto.PaymentRequest;
import com.platform.payment.dto.PaymentResponse;
import com.platform.payment.dto.RefundRequest;
import com.platform.payment.dto.RefundResponse;

public interface PaymentService {

    PaymentResponse processPayment(PaymentRequest request, String idempotencyKey, String correlationId);

    PaymentResponse getPaymentById(String paymentId);

    PaymentResponse getPaymentByOrderId(String orderId);

    RefundResponse refundPayment(String paymentId, RefundRequest request, String correlationId);
}
