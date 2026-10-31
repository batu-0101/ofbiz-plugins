package org.apache.ofbiz.square;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/** Explicit, non-secret seller/fee configuration; credentials remain in Windows Credential Manager. */
public final class BooksSettings {
    private BooksSettings() { }

    public static Map<String, Object> save(DispatchContext ctx, Map<String, Object> input) {
        try {
            GenericValue actor = (GenericValue) input.get("userLogin");
            if (actor == null || !ctx.getSecurity().hasEntityPermission("ACCOUNTING", "_ADMIN", actor)) {
                return ServiceUtil.returnError("Accounting administrator access is required.");
            }
            String party = (String) input.get("partyId");
            if (EntityQuery.use(ctx.getDelegator()).from("PartyRole").where("partyId", party,
                    "roleTypeId", "INTERNAL_ORGANIZATIO").queryOne() == null) {
                return ServiceUtil.returnError("Choose an existing internal issuing organization.");
            }
            long bps = (Long) input.get("feeBps");
            long fixed = (Long) input.get("feeFixedCents");
            BooksMoney.charge(10000, true, bps, fixed);
            Map<String, String> values = Map.of("BB_SQUARE_MERCHANT", UtilProperties.getPropertyValue("square", "square.expectedMerchantId"),
                    "BB_CARD_FEE_ENABLED", String.valueOf(Boolean.TRUE.equals(input.get("feeEnabled"))),
                    "BB_CARD_FEE_BPS", Long.toString(bps), "BB_CARD_FEE_FIXED", Long.toString(fixed));
            for (Map.Entry<String, String> entry : values.entrySet()) {
                ctx.getDelegator().createOrStore(ctx.getDelegator().makeValue("PartyAttribute",
                        Map.of("partyId", party, "attrName", entry.getKey(), "attrValue", entry.getValue())));
            }
            return ServiceUtil.returnSuccess("Payment settings saved for " + party + ".");
        } catch (IllegalArgumentException e) {
            return ServiceUtil.returnError(e.getMessage());
        } catch (Exception e) {
            return ServiceUtil.returnError("Payment settings could not be saved. No partial change was committed.");
        }
    }

    public static String page(HttpServletRequest request, HttpServletResponse response) {
        try {
            LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
            DispatchContext ctx = dispatcher.getDispatchContext();
            GenericValue actor = (GenericValue) request.getSession().getAttribute("userLogin");
            if (actor == null || !ctx.getSecurity().hasEntityPermission("ACCOUNTING", "_ADMIN", actor)) {
                response.setStatus(403);
                return "error";
            }
            if ("POST".equals(request.getMethod())) {
                if (!BooksEvents.isPost(request) || !"true".equals(request.getParameter("confirmMerchant"))) {
                    response.setStatus(403);
                    request.setAttribute("booksError", "Confirm the issuing organization belongs to the connected Square account.");
                } else {
                    Map<String, Object> result = dispatcher.runSync("bbSavePaymentSettings", Map.of("userLogin", actor,
                            "partyId", request.getParameter("partyId"), "feeEnabled", "true".equals(request.getParameter("feeEnabled")),
                            "feeBps", Long.valueOf(request.getParameter("feeBps")),
                            "feeFixedCents", Long.valueOf(request.getParameter("feeFixedCents"))), 90, true);
                    request.setAttribute(ServiceUtil.isError(result) ? "booksError" : "booksSuccess",
                            ServiceUtil.isError(result) ? ServiceUtil.getErrorMessage(result) : result.get("successMessage"));
                }
            }
            List<GenericValue> organizations = EntityQuery.use(ctx.getDelegator()).from("PartyRole")
                    .where("roleTypeId", "INTERNAL_ORGANIZATIO").queryList();
            List<Map<String, String>> choices = new ArrayList<>();
            String selected = request.getParameter("partyId");
            for (GenericValue org : organizations) {
                String id = org.getString("partyId");
                GenericValue group = BooksServices.one(ctx.getDelegator(), "PartyGroup", "partyId", id);
                String binding = BooksServices.setting(ctx.getDelegator(), id, "BB_SQUARE_MERCHANT", "");
                boolean bound = UtilProperties.getPropertyValue("square", "square.expectedMerchantId").equals(binding);
                choices.add(Map.of("partyId", id, "name", group == null ? id : group.getString("groupName"),
                        "enabled", String.valueOf(bound)));
                if ((selected == null || selected.isBlank()) && bound) {
                    selected = id;
                }
            }
            request.setAttribute("booksOrganizations", choices);
            request.setAttribute("booksSelectedParty", selected == null ? "" : selected);
            if (selected != null && !selected.isBlank()) {
                request.setAttribute("booksPaymentEnabled", UtilProperties.getPropertyValue("square", "square.expectedMerchantId")
                        .equals(BooksServices.setting(ctx.getDelegator(), selected, "BB_SQUARE_MERCHANT", "")));
                request.setAttribute("booksFeeEnabled", Boolean.parseBoolean(BooksServices.setting(ctx.getDelegator(), selected,
                        "BB_CARD_FEE_ENABLED", "false")));
                request.setAttribute("booksFeeBps", BooksServices.setting(ctx.getDelegator(), selected, "BB_CARD_FEE_BPS", "0"));
                request.setAttribute("booksFeeFixed", BooksServices.setting(ctx.getDelegator(), selected, "BB_CARD_FEE_FIXED", "0"));
            }
            if (request.getSession().getAttribute("booksCsrf") == null) {
                request.getSession().setAttribute("booksCsrf", java.util.UUID.randomUUID().toString());
            }
            request.setAttribute("booksCsrf", request.getSession().getAttribute("booksCsrf"));
            return "success";
        } catch (Exception e) {
            request.setAttribute("booksError", "Enter a whole number of basis points and fixed cents, then try again.");
            return "error";
        }
    }
}
