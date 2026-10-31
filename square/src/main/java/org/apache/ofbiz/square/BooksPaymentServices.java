package org.apache.ofbiz.square;

import java.math.BigDecimal;
import java.util.Map;

import org.apache.ofbiz.accounting.invoice.InvoiceWorker;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Square handoff and exact capture reconciliation into native OFBiz receivables. */
public final class BooksPaymentServices {
    private BooksPaymentServices() { }

    private static GenericValue checkout(DispatchContext ctx, Map<String, Object> input) throws Exception {
        Delegator d = ctx.getDelegator();
        String id = (String) input.get("checkoutId");
        GenericValue c = BooksServices.one(d, "BbCheckout", "checkoutId", id);
        if (c == null) {
            throw new IllegalArgumentException("This checkout is not available.");
        }
        GenericValue invoice = BooksServices.one(d, "Invoice", "invoiceId", c.getString("invoiceId"));
        BooksServices.access(ctx, (GenericValue) input.get("userLogin"), invoice);
        BooksServices.lock(d, "Invoice", "invoiceId", invoice.getString("invoiceId"));
        return BooksServices.lock(d, "BbCheckout", "checkoutId", id);
    }

    static void merchant(GenericValue invoice) throws Exception {
        String organization = UtilProperties.getPropertyValue("books", "books.squarePartyId");
        String binding = BooksServices.setting(invoice.getDelegator(), invoice.getString("partyIdFrom"), "BB_SQUARE_MERCHANT", "");
        boolean bound = UtilProperties.getPropertyValue("square", "square.expectedMerchantId").equals(binding);
        if (!bound && (organization.isBlank() || !organization.equals(invoice.getString("partyIdFrom")))) {
            throw new IllegalArgumentException("Square checkout is not enabled for this invoice's issuing organization. "
                    + "The office must bind the real Home & Beyond Services organization before accepting live payments.");
        }
    }

    private static SquareCheckoutClient client() {
        return new SquareCheckoutClient(System.getenv("OFBIZ_SQUARE_ACCESS_TOKEN"));
    }

    public static Map<String, Object> start(DispatchContext ctx, Map<String, Object> input) {
        try {
            GenericValue c = checkout(ctx, input);
            GenericValue invoice = BooksServices.one(ctx.getDelegator(), "Invoice", "invoiceId", c.getString("invoiceId"));
            merchant(invoice);
            BooksMoney.Charge current = BooksServices.charge(ctx.getDelegator(), invoice);
            BooksMoney.requireConsent(current, c.getLong("principalCents"), c.getLong("feeCents"), c.getLong("totalCents"), true);
            if (!"ACCEPTED".equals(c.getString("status")) && !"LINKED".equals(c.getString("status"))) {
                throw new IllegalArgumentException("This checkout is closed. Refresh the invoice to review its payment status.");
            }
            for (GenericValue other : BooksServices.rows(ctx.getDelegator(), "BbCheckout", "invoiceId",
                    invoice.getString("invoiceId"), "checkoutId")) {
                if (!c.getString("checkoutId").equals(other.getString("checkoutId")) && "LINKED".equals(other.getString("status"))) {
                    throw new IllegalArgumentException("An earlier Square checkout is still open. "
                            + "Refresh its payment status before starting another.");
                }
            }
            if (c.getString("paymentUrl") == null) {
                if (c.getString("providerRequestId") == null) {
                    throw new IllegalArgumentException("This older checkout has no provider request identity. Contact the office before retrying.");
                }
                SquareCheckoutClient square = client();
                square.verifyLocation(c.getString("locationId"), UtilProperties.getPropertyValue("square", "square.expectedMerchantId"));
                SquareCheckoutClient.Link link = square.create(c.getString("checkoutId"), c.getString("providerRequestId"),
                        invoice.getString("invoiceId"),
                        c.getString("locationId"), current);
                c.set("providerLinkId", link.id());
                c.set("providerOrderId", link.orderId());
                c.set("paymentUrl", link.url());
                c.set("status", "LINKED");
                c.store();
            }
            Map<String, Object> result = ServiceUtil.returnSuccess();
            result.put("paymentUrl", c.getString("paymentUrl"));
            return result;
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ServiceUtil.returnError("Square checkout was interrupted. Retry will reuse the same payment request.");
        } catch (Exception e) {
            return ServiceUtil.returnError("Square checkout could not be opened. No invoice payment was recorded. "
                    + "Retry will reuse the same request.");
        }
    }

    public static Map<String, Object> refresh(DispatchContext ctx, Map<String, Object> input) {
        try {
            GenericValue c = checkout(ctx, input);
            if ("PAID".equals(c.getString("status")) || "REVIEW".equals(c.getString("status"))) {
                return state(c.getString("status"));
            }
            if (c.getString("providerOrderId") == null) {
                return state("NOT_STARTED");
            }
            GenericValue invoice = BooksServices.one(ctx.getDelegator(), "Invoice", "invoiceId", c.getString("invoiceId"));
            merchant(invoice);
            SquareCheckoutClient square = client();
            square.verifyLocation(c.getString("locationId"), UtilProperties.getPropertyValue("square", "square.expectedMerchantId"));
            SquareCheckoutClient.Capture captured = square.capture(c.getString("checkoutId"), c.getString("providerOrderId"),
                    c.getString("locationId"), c.getLong("totalCents"));
            if (captured == null) {
                return state("PENDING");
            }
            return settleVerified(ctx, c, invoice, captured);
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ServiceUtil.returnError("Square verification was interrupted. Refresh payment status to retry.");
        } catch (Exception e) {
            return ServiceUtil.returnError("The completed payment could not be verified or posted. "
                    + "No partial payment was committed; retry status refresh.");
        }
    }

    static Map<String, Object> settleVerified(DispatchContext ctx, GenericValue c, GenericValue invoice,
            SquareCheckoutClient.Capture captured) throws Exception {
        if (captured.totalCents() != c.getLong("totalCents") || !"USD".equals(captured.currency())) {
            throw new IllegalArgumentException("The verified capture does not match the accepted charge.");
        }
        if ("PAID".equals(c.getString("status"))) {
            if (!captured.paymentId().equals(c.getString("providerPaymentId"))) {
                throw new IllegalArgumentException("A different capture is already recorded for this checkout.");
            }
            return state("PAID");
        }
        BigDecimal principal = BooksMoney.amount(c.getLong("principalCents"));
        if (!"INVOICE_READY".equals(invoice.getString("statusId"))
                || InvoiceWorker.getInvoiceNotApplied(invoice).compareTo(principal) < 0) {
            c.set("status", "REVIEW");
            c.set("providerPaymentId", captured.paymentId());
            c.set("reviewReason", "Square captured the payment after the invoice changed or became unpayable. Review/refund required.");
            c.store();
            return state("REVIEW");
        }
        String feeInvoiceId = null;
        if (c.getLong("feeCents") > 0) {
            feeInvoiceId = (String) BooksServices.nativeService(ctx, "createInvoice", UtilMisc.toMap("invoiceTypeId", "SALES_INVOICE",
                    "partyIdFrom", invoice.getString("partyIdFrom"), "partyId", invoice.getString("partyId"), "currencyUomId", "USD",
                    "description", "Accepted card fee for invoice " + invoice.getString("invoiceId"))).get("invoiceId");
            BooksServices.nativeService(ctx, "createInvoiceItem", Map.of("invoiceId", feeInvoiceId, "invoiceItemTypeId", "FEE",
                    "quantity", BigDecimal.ONE, "amount", BooksMoney.amount(c.getLong("feeCents")), "description", "Accepted card processing fee"));
            BooksServices.nativeService(ctx, "setInvoiceStatus", Map.of("invoiceId", feeInvoiceId, "statusId", "INVOICE_READY"));
            GenericValue feeInvoice = BooksServices.one(ctx.getDelegator(), "Invoice", "invoiceId", feeInvoiceId);
            if (!"USD".equals(feeInvoice.getString("currencyUomId"))
                    || BooksMoney.cents(InvoiceWorker.getInvoiceNotApplied(feeInvoice)) != c.getLong("feeCents")) {
                throw new IllegalArgumentException("The fee invoice currency or balance changed. The capture requires accounting review.");
            }
        }
        String paymentId = (String) BooksServices.nativeService(ctx, "createPayment", UtilMisc.toMap("paymentTypeId", "CUSTOMER_PAYMENT",
                "partyIdFrom", invoice.getString("partyId"), "partyIdTo", invoice.getString("partyIdFrom"), "paymentMethodTypeId", "CREDIT_CARD",
                "statusId", "PMNT_RECEIVED", "currencyUomId", "USD", "amount", BooksMoney.amount(c.getLong("totalCents")),
                "paymentRefNum", captured.paymentId(), "comments", "Square checkout " + c.getString("checkoutId"))).get("paymentId");
        Map<String, Object> applied = BooksServices.nativeService(ctx, "createPaymentApplication", Map.of("invoiceId", invoice.getString("invoiceId"),
                "paymentId", paymentId, "amountApplied", principal));
        if (principal.compareTo((BigDecimal) applied.get("amountApplied")) != 0) {
            throw new IllegalArgumentException("The principal could not be applied exactly. The capture requires accounting review.");
        }
        if (feeInvoiceId != null) {
            Map<String, Object> feeApplied = BooksServices.nativeService(ctx, "createPaymentApplication",
                    Map.of("invoiceId", feeInvoiceId, "paymentId", paymentId,
                    "amountApplied", BooksMoney.amount(c.getLong("feeCents"))));
            if (BooksMoney.cents((BigDecimal) feeApplied.get("amountApplied")) != c.getLong("feeCents")) {
                throw new IllegalArgumentException("The fee could not be applied exactly. The capture requires accounting review.");
            }
            c.set("feeInvoiceId", feeInvoiceId);
        }
        c.set("status", "PAID");
        c.set("providerPaymentId", captured.paymentId());
        c.set("processingFeeCents", captured.processingFeeCents());
        c.set("paymentId", paymentId);
        c.store();
        return state("PAID");
    }

    private static Map<String, Object> state(String value) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("paymentState", value);
        return result;
    }
}
