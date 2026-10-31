package org.apache.ofbiz.square;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ofbiz.accounting.invoice.InvoiceWorker;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/** Session-authorized document display and POST-only approval/fee consent. */
public final class BooksEvents {
    private BooksEvents() { }

    private static DispatchContext context(HttpServletRequest request) {
        return ((LocalDispatcher) request.getAttribute("dispatcher")).getDispatchContext();
    }

    private static GenericValue user(HttpServletRequest request) {
        return request.getSession(false) == null ? null : (GenericValue) request.getSession(false).getAttribute("userLogin");
    }

    static boolean isPost(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && request.getSession(false) != null
                && request.getParameter("booksCsrf") != null
                && request.getParameter("booksCsrf").equals(request.getSession(false).getAttribute("booksCsrf"));
    }

    public static String list(HttpServletRequest request, HttpServletResponse response) {
        try {
            DispatchContext ctx = context(request);
            GenericValue actor = user(request);
            if (actor == null) {
                response.setStatus(403);
                return "error";
            }
            EntityQuery quotes = EntityQuery.use(ctx.getDelegator()).from("Quote").orderBy("-issueDate").maxRows(100);
            EntityQuery invoices = EntityQuery.use(ctx.getDelegator()).from("Invoice").orderBy("-invoiceDate").maxRows(100);
            if (!ctx.getSecurity().hasEntityPermission("ACCOUNTING", "_ADMIN", actor)) {
                if (actor.getString("partyId") == null) {
                    response.setStatus(403);
                    return "error";
                }
                quotes.where("partyId", actor.getString("partyId"));
                invoices.where("partyId", actor.getString("partyId"), "invoiceTypeId", "SALES_INVOICE");
            } else {
                invoices.where("invoiceTypeId", "SALES_INVOICE");
            }
            request.setAttribute("booksQuotes", quotes.queryList());
            request.setAttribute("booksInvoices", invoices.queryList());
            return "success";
        } catch (Exception e) {
            request.setAttribute("booksError", "The documents could not be loaded. Refresh to retry.");
            return "error";
        }
    }

    public static String document(HttpServletRequest request, HttpServletResponse response) {
        try {
            DispatchContext ctx = context(request);
            Delegator d = ctx.getDelegator();
            String quoteId = request.getParameter("quoteId");
            String invoiceId = request.getAttribute("booksInvoiceId") == null ? request.getParameter("invoiceId")
                    : (String) request.getAttribute("booksInvoiceId");
            GenericValue conversion = null;
            GenericValue q = null;
            if (quoteId != null && !quoteId.isBlank()) {
                q = BooksServices.one(d, "Quote", "quoteId", quoteId);
                BooksServices.access(ctx, user(request), q);
                conversion = BooksServices.one(d, "BbQuoteConversion", "quoteId", quoteId);
                if (conversion != null) {
                    invoiceId = conversion.getString("invoiceId");
                } else {
                    invoiceId = null;
                }
            }
            Map<String, Object> view = new LinkedHashMap<>();
            if (invoiceId != null && !invoiceId.isBlank()) {
                GenericValue inv = BooksServices.one(d, "Invoice", "invoiceId", invoiceId);
                BooksServices.access(ctx, user(request), inv);
                view.putAll(Map.of("kind", "Invoice", "id", invoiceId, "currency", inv.getString("currencyUomId"),
                        "status", inv.getString("statusId"), "total", InvoiceWorker.getInvoiceTotal(inv),
                        "balance", InvoiceWorker.getInvoiceNotApplied(inv), "partyId", inv.getString("partyId")));
                List<Map<String, Object>> lines = new java.util.ArrayList<>();
                for (GenericValue item : BooksServices.rows(d, "InvoiceItem", "invoiceId", invoiceId, "invoiceItemSeqId")) {
                    Map<String, Object> line = new LinkedHashMap<>(item.getAllFields());
                    line.put("lineTotal", InvoiceWorker.getInvoiceItemTotal(item));
                    lines.add(line);
                }
                view.put("lines", lines);
                view.put("terms", BooksServices.rows(d, "InvoiceTerm", "invoiceId", invoiceId, "termDays,invoiceTermId"));
                view.put("description", inv.getString("description"));
                try {
                    BooksMoney.Charge charge = BooksServices.charge(d, inv);
                    view.put("principalCents", charge.principal());
                    view.put("feeCents", charge.fee());
                    view.put("totalCents", charge.total());
                    view.put("principal", BooksMoney.amount(charge.principal()));
                    view.put("fee", BooksMoney.amount(charge.fee()));
                    view.put("cardTotal", BooksMoney.amount(charge.total()));
                } catch (IllegalArgumentException e) {
                    view.put("paymentMessage", e.getMessage());
                }
                view.put("checkouts", BooksServices.rows(d, "BbCheckout", "invoiceId", invoiceId, "-createdStamp"));
            } else if (q != null) {
                view.putAll(BooksServices.quote(d, q));
                view.putAll(Map.of("kind", "Estimate", "id", quoteId, "currency", q.getString("currencyUomId"),
                        "status", q.getString("statusId"), "partyId", q.getString("partyId")));
                view.put("description", q.getString("description"));
                view.put("canApprove", "QUO_APPROVED".equals(q.getString("statusId")));
            } else {
                throw new IllegalArgumentException("Choose an estimate or invoice to open.");
            }
            if (conversion != null) {
                view.put("estimateId", quoteId);
                view.put("approvedAt", conversion.getTimestamp("approvedAt"));
            }
            if (request.getSession().getAttribute("booksCsrf") == null) {
                request.getSession().setAttribute("booksCsrf", UUID.randomUUID().toString());
            }
            request.setAttribute("booksCsrf", request.getSession().getAttribute("booksCsrf"));
            request.setAttribute("booksDocument", view);
            return "success";
        } catch (IllegalArgumentException e) {
            request.setAttribute("booksError", e.getMessage());
            response.setStatus(403);
            return "error";
        } catch (Exception e) {
            request.setAttribute("booksError", "The document could not be loaded. Refresh to retry.");
            response.setStatus(500);
            return "error";
        }
    }

    public static String approve(HttpServletRequest request, HttpServletResponse response) {
        return mutate(request, response, "bbApproveEstimate");
    }

    public static String accept(HttpServletRequest request, HttpServletResponse response) {
        return mutate(request, response, "bbAcceptCardFee");
    }

    public static String refreshPayment(HttpServletRequest request, HttpServletResponse response) {
        if (!isPost(request)) {
            response.setStatus(403);
            return "error";
        }
        try {
            Map<String, Object> result = context(request).getDispatcher().runSync("bbRefreshSquarePayment",
                    Map.of("checkoutId", request.getParameter("checkoutId"), "userLogin", user(request)), 90, true);
            if (ServiceUtil.isError(result)) {
                request.setAttribute("booksError", ServiceUtil.getErrorMessage(result));
            } else {
                String state = (String) result.get("paymentState");
                request.setAttribute("booksSuccess", switch (state) {
                case "PAID" -> "Square verified the completed payment. The invoice balance has been updated.";
                case "REVIEW" -> "Square captured funds that require office review. They were not applied to this invoice.";
                case "NOT_STARTED" -> "No Square checkout was opened and no payment was recorded.";
                default -> "Square has not reported a completed payment. Your invoice remains unpaid.";
                });
            }
            return document(request, response);
        } catch (Exception e) {
            request.setAttribute("booksError", "Payment status could not be verified. Refresh to retry.");
            return document(request, response);
        }
    }

    private static String mutate(HttpServletRequest request, HttpServletResponse response, String service) {
        if (!isPost(request)) {
            response.setStatus(403);
            request.setAttribute("booksError", "Refresh the document and use its action button to continue.");
            return "error";
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("userLogin", user(request));
            if ("bbApproveEstimate".equals(service)) {
                input.put("quoteId", request.getParameter("quoteId"));
                input.put("snapshotDigest", request.getParameter("snapshotDigest"));
            } else {
                input.put("invoiceId", request.getParameter("invoiceId"));
                for (String amount : List.of("principalCents", "feeCents", "totalCents")) {
                    input.put(amount, Long.valueOf(request.getParameter(amount)));
                }
                input.put("acknowledged", "true".equals(request.getParameter("acknowledged")));
            }
            Map<String, Object> result = context(request).getDispatcher().runSync(service, input, 90, true);
            if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
                request.setAttribute("booksError", ServiceUtil.getErrorMessage(result));
                response.setStatus(409);
            } else if (result.get("invoiceId") != null) {
                request.setAttribute("booksInvoiceId", result.get("invoiceId"));
                request.setAttribute("booksSuccess", "Approved. Your estimate is now invoice " + result.get("invoiceId") + ".");
            } else {
                Map<String, Object> checkout = context(request).getDispatcher().runSync("bbStartSquareCheckout",
                        Map.of("checkoutId", result.get("checkoutId"), "userLogin", user(request)), 90, true);
                if (ServiceUtil.isError(checkout)) {
                    request.setAttribute("booksError", ServiceUtil.getErrorMessage(checkout));
                } else {
                    request.setAttribute("booksPaymentUrl", checkout.get("paymentUrl"));
                    request.setAttribute("booksSuccess", "Your total is accepted. Continue with Square to complete payment.");
                }
            }
            return document(request, response);
        } catch (Exception e) {
            request.setAttribute("booksError", "The request could not be completed. Refresh and review the current document before retrying.");
            response.setStatus(400);
            return "error";
        }
    }
}
