package org.apache.ofbiz.square;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.apache.ofbiz.accounting.invoice.InvoiceWorker;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Beyond Books approval semantics, backed by OFBiz's native invoice services and transactions. */
public final class BooksServices {
    private BooksServices() { }

    static GenericValue one(Delegator d, String entity, String key, String id) throws Exception {
        return EntityQuery.use(d).from(entity).where(key, id).queryOne();
    }

    static List<GenericValue> rows(Delegator d, String entity, String key, String id, String sort) throws Exception {
        return EntityQuery.use(d).from(entity).where(key, id).orderBy(sort.split(",")).queryList();
    }

    static void access(DispatchContext ctx, GenericValue user, GenericValue document) {
        if (user == null || document == null || (!ctx.getSecurity().hasEntityPermission("ACCOUNTING", "_ADMIN", user)
                && (user.getString("partyId") == null || !user.getString("partyId").equals(document.getString("partyId"))))) {
            throw new IllegalArgumentException("This document is not available to your account.");
        }
    }

    // A database write lock (including across processes), retained until the service transaction finishes.
    static GenericValue lock(Delegator d, String entity, String key, String id) throws Exception {
        int found = d.storeByCondition(entity, Map.of(key, id), EntityCondition.makeCondition(key, id));
        if (found != 1) {
            throw new IllegalArgumentException("The document no longer exists.");
        }
        return one(d, entity, key, id);
    }

    static String digest(String input) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String canonical(GenericValue value) throws Exception {
        Map<String, Object> fields = new TreeMap<>(value.getAllFields());
        fields.keySet().removeIf(k -> k.endsWith("Stamp") || k.equals("lastUpdatedTxStamp") || k.equals("createdTxStamp"));
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fields);
    }

    static Map<String, Object> quote(Delegator d, GenericValue q) throws Exception {
        String id = q.getString("quoteId");
        List<GenericValue> items = rows(d, "QuoteItem", "quoteId", id, "quoteItemSeqId");
        List<GenericValue> adjustments = rows(d, "QuoteAdjustment", "quoteId", id, "quoteAdjustmentId");
        List<GenericValue> terms = rows(d, "QuoteTerm", "quoteId", id, "termTypeId");
        List<Map<String, Object>> lines = new ArrayList<>();
        StringBuilder evidence = new StringBuilder(canonical(q));
        BigDecimal total = BigDecimal.ZERO;
        for (GenericValue item : items) {
            BigDecimal qty = item.getBigDecimal("quantity");
            BigDecimal price = item.getBigDecimal("quoteUnitPrice");
            if (qty == null || qty.signum() <= 0 || price == null || price.signum() < 0) {
                throw new IllegalArgumentException("Every estimate line needs a positive quantity and a nonnegative unit price.");
            }
            String description = item.getString("comments");
            GenericValue product = item.getString("productId") == null ? null : one(d, "Product", "productId", item.getString("productId"));
            if (description == null || description.isBlank()) {
                description = product == null ? "Estimate line " + item.getString("quoteItemSeqId") : product.getString("productName");
            }
            String type = "INV_FPROD_ITEM";
            if (product != null) {
                GenericValue mapping = EntityQuery.use(d).from("InvoiceItemTypeMap")
                        .where("invoiceItemMapKey", product.getString("productTypeId"), "invoiceTypeId", "SALES_INVOICE").queryOne();
                if (mapping != null) {
                    type = mapping.getString("invoiceItemTypeId");
                }
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("invoiceItemSeqId", item.getString("quoteItemSeqId"));
            line.put("description", Objects.toString(description, "Estimate item"));
            line.put("quantity", qty);
            line.put("amount", price);
            line.put("invoiceItemTypeId", type);
            for (String field : List.of("productId", "productFeatureId", "uomId")) {
                if (item.get(field) != null) {
                    line.put(field, item.get(field));
                }
            }
            // OFBiz rounds each extended line to the currency's scale; preserve the source quantity and price.
            BigDecimal lineTotal = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            line.put("lineTotal", lineTotal);
            total = total.add(lineTotal);
            lines.add(line);
            evidence.append(canonical(item)).append(line);
        }
        for (GenericValue adj : adjustments) {
            BigDecimal amount = adj.getBigDecimal("amount");
            if (amount == null) {
                throw new IllegalArgumentException("An estimate adjustment has no finalized amount. Finalize pricing before approval.");
            }
            GenericValue mapping = EntityQuery.use(d).from("InvoiceItemTypeMap")
                    .where("invoiceItemMapKey", adj.getString("quoteAdjustmentTypeId"), "invoiceTypeId", "SALES_INVOICE").queryOne();
            if (mapping == null) {
                throw new IllegalArgumentException("An estimate adjustment has no sales invoice mapping: " + adj.getString("quoteAdjustmentTypeId"));
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("description", Objects.toString(adj.get("description"), adj.getString("quoteAdjustmentTypeId")));
            line.put("quantity", BigDecimal.ONE);
            line.put("amount", amount);
            line.put("invoiceItemTypeId", mapping.getString("invoiceItemTypeId"));
            line.put("lineTotal", amount.setScale(2, RoundingMode.HALF_UP));
            for (String field : List.of("taxAuthPartyId", "taxAuthGeoId", "overrideGlAccountId")) {
                if (adj.get(field) != null) {
                    line.put(field, adj.get(field));
                }
            }
            lines.add(line);
            total = total.add(amount.setScale(2, RoundingMode.HALF_UP));
            evidence.append(canonical(adj));
        }
        for (GenericValue term : terms) {
            evidence.append(canonical(term));
        }
        if (items.isEmpty() || total.signum() <= 0) {
            throw new IllegalArgumentException("The estimate must contain priced lines and a positive total.");
        }
        return Map.of("lines", lines, "terms", terms, "total", total, "digest", digest(evidence.toString()));
    }

    static Map<String, Object> nativeService(DispatchContext ctx, String service, Map<String, Object> values) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>(values);
        // Actor authorization is enforced before entry. Only this bounded document operation uses the system identity.
        args.put("userLogin", one(ctx.getDelegator(), "UserLogin", "userLoginId", "system"));
        Map<String, Object> result = ctx.getDispatcher().runSync(service, args);
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new IllegalArgumentException(ServiceUtil.getErrorMessage(result));
        }
        return result;
    }

    public static Map<String, Object> approve(DispatchContext ctx, Map<String, Object> input) {
        try {
            Delegator d = ctx.getDelegator();
            String id = (String) input.get("quoteId");
            GenericValue user = (GenericValue) input.get("userLogin");
            access(ctx, user, one(d, "Quote", "quoteId", id));
            GenericValue q = lock(d, "Quote", "quoteId", id);
            access(ctx, user, q);
            GenericValue converted = one(d, "BbQuoteConversion", "quoteId", id);
            if (converted != null) {
                return success("invoiceId", converted.getString("invoiceId"));
            }
            Timestamp now = UtilDateTime.nowTimestamp();
            if (!"QUO_APPROVED".equals(q.getString("statusId"))) {
                throw new IllegalArgumentException("Only an issued, approved estimate can be accepted. "
                        + "Draft, rejected and ordered quotes cannot convert.");
            }
            if ((q.getTimestamp("validThruDate") != null && !q.getTimestamp("validThruDate").after(now))
                    || (q.getTimestamp("validFromDate") != null && q.getTimestamp("validFromDate").after(now))) {
                throw new IllegalArgumentException("This estimate is outside its validity dates. "
                        + "Ask the office for the current estimate.");
            }
            Map<String, Object> snapshot = quote(d, q);
            if (!snapshot.get("digest").equals(input.get("snapshotDigest"))) {
                throw new IllegalArgumentException("The estimate changed. Review its current lines, terms and total before approving.");
            }
            GenericValue store = one(d, "ProductStore", "productStoreId", q.getString("productStoreId"));
            if (store == null || store.getString("payToPartyId") == null) {
                throw new IllegalArgumentException("Assign the estimate to a product store with an issuing organization first.");
            }
            String currency = q.getString("currencyUomId");
            if (!"USD".equals(currency)) {
                throw new IllegalArgumentException("This local Beyond Books workflow currently accepts USD estimates only.");
            }
            Map<String, Object> header = UtilMisc.toMap("invoiceTypeId", "SALES_INVOICE", "partyIdFrom", store.getString("payToPartyId"),
                    "partyId", q.getString("partyId"), "currencyUomId", currency, "description", q.getString("description"),
                    "referenceNumber", id, "invoiceDate", now);
            String invoiceId = (String) nativeService(ctx, "createInvoice", header).get("invoiceId");
            for (Map<String, Object> line : (List<Map<String, Object>>) snapshot.get("lines")) {
                Map<String, Object> copy = new LinkedHashMap<>(line);
                copy.remove("lineTotal");
                copy.put("invoiceId", invoiceId);
                nativeService(ctx, "createInvoiceItem", copy);
            }
            for (GenericValue term : (List<GenericValue>) snapshot.get("terms")) {
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("invoiceId", invoiceId);
                for (String field : List.of("termTypeId", "termDays", "textValue", "description", "uomId")) {
                    if (term.get(field) != null) {
                        copy.put(field, term.get(field));
                    }
                }
                if (term.get("termValue") != null) {
                    copy.put("termValue", new BigDecimal(term.get("termValue").toString()));
                }
                String seq = term.getString("quoteItemSeqId");
                if (seq != null && !seq.isBlank() && !"_NA_".equals(seq)) {
                    copy.put("invoiceItemSeqId", seq);
                }
                nativeService(ctx, "createInvoiceTerm", copy);
            }
            GenericValue invoice = one(d, "Invoice", "invoiceId", invoiceId);
            if (!currency.equals(invoice.getString("currencyUomId"))
                    || InvoiceWorker.getInvoiceTotal(invoice).compareTo((BigDecimal) snapshot.get("total")) != 0) {
                throw new IllegalArgumentException("Invoice currency or total differs from the approved estimate. Nothing was converted.");
            }
            nativeService(ctx, "setInvoiceStatus", Map.of("invoiceId", invoiceId, "statusId", "INVOICE_READY"));
            d.create("BbQuoteConversion", UtilMisc.toMap("quoteId", id, "invoiceId", invoiceId, "approvedAt", now,
                    "approvedBy", user.getString("userLoginId"), "snapshotDigest", snapshot.get("digest")));
            q.set("statusId", "QUO_BB_CONVERTED");
            q.store();
            return success("invoiceId", invoiceId);
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            return ServiceUtil.returnError("The estimate could not be converted. No partial invoice was committed; refresh and retry.");
        }
    }

    public static Map<String, Object> guardQuote(DispatchContext ctx, Map<String, Object> input) {
        try {
            String id = (String) input.get("quoteId");
            if (id == null && input.get("quoteAdjustmentId") != null) {
                GenericValue adj = one(ctx.getDelegator(), "QuoteAdjustment", "quoteAdjustmentId", (String) input.get("quoteAdjustmentId"));
                id = adj == null ? null : adj.getString("quoteId");
            }
            if (id != null && one(ctx.getDelegator(), "Quote", "quoteId", id) != null) {
                GenericValue q = lock(ctx.getDelegator(), "Quote", "quoteId", id);
                if ("QUO_BB_CONVERTED".equals(q.getString("statusId")) || one(ctx.getDelegator(), "BbQuoteConversion", "quoteId", id) != null) {
                    return ServiceUtil.returnError("This estimate was converted to an invoice and is immutable. Create a new quote for a revision.");
                }
            }
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            return ServiceUtil.returnError("The estimate could not be locked for editing. Retry after the current approval finishes.");
        }
    }

    static String setting(Delegator d, String organization, String name, String fallback) throws Exception {
        GenericValue value = EntityQuery.use(d).from("PartyAttribute").where("partyId", organization, "attrName", name).queryOne();
        return value == null ? fallback : value.getString("attrValue");
    }

    static BooksMoney.Charge charge(Delegator d, GenericValue invoice) throws Exception {
        if (!"SALES_INVOICE".equals(invoice.getString("invoiceTypeId")) || !"USD".equals(invoice.getString("currencyUomId"))
                || !List.of("INVOICE_SENT", "INVOICE_APPROVED", "INVOICE_READY").contains(invoice.getString("statusId"))) {
            throw new IllegalArgumentException("This invoice is not payable. Draft, paid, canceled and written-off invoices cannot open checkout.");
        }
        BigDecimal total = InvoiceWorker.getInvoiceTotal(invoice);
        BigDecimal paid = InvoiceWorker.getInvoiceApplied(invoice);
        BigDecimal due = total.subtract(paid);
        // Walk the native installment schedule, just as Beyond Books collects the next unpaid milestone.
        BigDecimal scheduled = BigDecimal.ZERO;
        BigDecimal remainingPaid = paid;
        BigDecimal nextInstallment = null;
        for (GenericValue term : rows(d, "InvoiceTerm", "invoiceId", invoice.getString("invoiceId"), "termDays,invoiceTermId")) {
            GenericValue type = one(d, "TermType", "termTypeId", term.getString("termTypeId"));
            if (type != null && "FIN_PAYMENT_TERM".equals(type.getString("parentTypeId"))) {
                BigDecimal percentage = term.getBigDecimal("termValue");
                if (percentage == null || percentage.signum() <= 0 || percentage.compareTo(BigDecimal.valueOf(100)) > 0) {
                    throw new IllegalArgumentException("The invoice installment schedule is invalid.");
                }
                BigDecimal installment = total.multiply(percentage).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                scheduled = scheduled.add(installment);
                if (scheduled.compareTo(total) > 0) {
                    throw new IllegalArgumentException("The installment schedule exceeds the invoice total.");
                }
                if (remainingPaid.compareTo(installment) < 0 && nextInstallment == null) {
                    nextInstallment = installment.subtract(remainingPaid).min(due);
                }
                remainingPaid = remainingPaid.subtract(installment).max(BigDecimal.ZERO);
            }
        }
        String org = invoice.getString("partyIdFrom");
        return BooksMoney.charge(BooksMoney.cents(nextInstallment == null ? due : nextInstallment),
                "true".equals(setting(d, org, "BB_CARD_FEE_ENABLED", UtilProperties.getPropertyValue("books", "books.cardFeeEnabled"))),
                Long.parseLong(setting(d, org, "BB_CARD_FEE_BPS", UtilProperties.getPropertyValue("books", "books.cardFeeBps", "0"))),
                Long.parseLong(setting(d, org, "BB_CARD_FEE_FIXED", UtilProperties.getPropertyValue("books", "books.cardFeeFixedCents", "0"))));
    }

    public static Map<String, Object> acceptFee(DispatchContext ctx, Map<String, Object> input) {
        try {
            Delegator d = ctx.getDelegator();
            String invoiceId = (String) input.get("invoiceId");
            GenericValue user = (GenericValue) input.get("userLogin");
            access(ctx, user, one(d, "Invoice", "invoiceId", invoiceId));
            GenericValue invoice = lock(d, "Invoice", "invoiceId", invoiceId);
            access(ctx, user, invoice);
            BooksMoney.Charge current = charge(d, invoice);
            BooksMoney.requireConsent(current, (Long) input.get("principalCents"), (Long) input.get("feeCents"),
                    (Long) input.get("totalCents"), Boolean.TRUE.equals(input.get("acknowledged")));
            String key = digest(invoiceId + ":" + current + ":" + InvoiceWorker.getInvoiceApplied(invoice));
            GenericValue existing = EntityQuery.use(d).from("BbCheckout").where("requestKey", key).queryOne();
            if (existing != null) {
                return success("checkoutId", existing.getString("checkoutId"));
            }
            String id = d.getNextSeqId("BbCheckout");
            Map<String, Object> amounts = UtilMisc.toMap("checkoutId", id, "principalCents", current.principal(), "feeCents", current.fee(),
                    "totalCents", current.total(), "currencyUomId", invoice.getString("currencyUomId"));
            Map<String, Object> checkout = new LinkedHashMap<>(amounts);
            checkout.putAll(Map.of("invoiceId", invoiceId, "requestKey", key, "status", "ACCEPTED",
                    "locationId", UtilProperties.getPropertyValue("books", "books.squareLocationId"),
                    "providerRequestId", java.util.UUID.randomUUID().toString()));
            d.create("BbCheckout", checkout);
            Map<String, Object> consent = new LinkedHashMap<>(amounts);
            consent.putAll(Map.of("acceptedAt", UtilDateTime.nowTimestamp(), "acceptedBy", user.getString("userLoginId"),
                    "noticeVersion", "BB_FEE_V1"));
            d.create("BbFeeConsent", consent);
            return success("checkoutId", id);
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            return ServiceUtil.returnError("The payment amount could not be verified. Refresh the invoice before trying again.");
        }
    }

    private static Map<String, Object> success(String name, String value) {
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put(name, value);
        return result;
    }
}
