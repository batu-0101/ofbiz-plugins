package org.apache.ofbiz.square;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Hosted checkout; never accepts card data and never trusts a browser's success redirect. */
public final class SquareCheckoutClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Transport transport;

    interface Transport {
        JsonNode request(String method, String path, Map<String, Object> body) throws Exception;
    }

    public record Link(String id, String orderId, String url) { }
    public record Capture(String paymentId, long totalCents, String currency, long processingFeeCents) { }

    public SquareCheckoutClient(String token) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        transport = (method, path, body) -> {
            if (token == null || token.isBlank()) {
                throw new IOException("Square is not connected in Windows Credential Manager.");
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://connect.squareup.com/v2/" + path))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                    .header("Square-Version", "2026-09-16").header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> reply = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (reply.statusCode() < 200 || reply.statusCode() >= 300) {
                throw new IOException("Square could not complete the request (HTTP " + reply.statusCode() + "). Retry uses the same checkout.");
            }
            JsonNode result = JSON.readTree(reply.body());
            if (result == null || !result.isObject() || result.path("errors").size() > 0) {
                throw new IOException("Square returned an incomplete response. Retry uses the same checkout.");
            }
            return result;
        };
    }

    SquareCheckoutClient(Transport transport) {
        this.transport = transport;
    }

    void verifyLocation(String location, String merchant) throws Exception {
        JsonNode actual = transport.request("GET", "locations/" + identifier(location), null).path("location");
        if (!location.equals(actual.path("id").asText()) || !merchant.equals(actual.path("merchant_id").asText())
                || !"ACTIVE".equals(actual.path("status").asText()) || !"USD".equals(actual.path("currency").asText())) {
            throw new IllegalArgumentException("The checkout location does not match the connected active USD merchant.");
        }
    }

    Link create(String checkoutId, String requestId, String invoiceId, String location, BooksMoney.Charge amount) throws Exception {
        if (amount.total() > 5000000 || amount.principal() <= 0 || amount.fee() < 0
                || Math.addExact(amount.principal(), amount.fee()) != amount.total()) {
            throw new IllegalArgumentException("The card total is outside the supported checkout amount.");
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(Map.of("name", "Invoice " + invoiceId, "quantity", "1",
                "base_price_money", Map.of("amount", amount.principal(), "currency", "USD")));
        if (amount.fee() > 0) {
            lines.add(Map.of("name", "Card processing fee (accepted before checkout)", "quantity", "1",
                    "base_price_money", Map.of("amount", amount.fee(), "currency", "USD")));
        }
        JsonNode result = transport.request("POST", "online-checkout/payment-links", Map.of(
                "idempotency_key", "ofbiz-books-" + identifier(requestId), "description", "OFBiz invoice " + invoiceId,
                "payment_note", "OFBiz invoice " + invoiceId,
                "order", Map.of("location_id", location, "reference_id", "BB-" + checkoutId, "line_items", lines),
                "checkout_options", Map.of("allow_tipping", false, "ask_for_shipping_address", false, "enable_loyalty", false,
                        "accepted_payment_methods", Map.of("apple_pay", true, "google_pay", true,
                                "cash_app_pay", false, "afterpay_clearpay", false))));
        JsonNode link = result.path("payment_link");
        String id = identifier(link.path("id").asText());
        String order = identifier(link.path("order_id").asText());
        String url = link.path("url").asText();
        URI parsed = URI.create(url);
        if (!"https".equals(parsed.getScheme()) || parsed.getUserInfo() != null
                || !("square.link".equals(parsed.getHost()) || "checkout.square.site".equals(parsed.getHost()))) {
            throw new IllegalArgumentException("Square did not return a recognized secure checkout URL.");
        }
        return new Link(id, order, url);
    }

    Capture capture(String checkoutId, String orderId, String location, long expectedTotal) throws Exception {
        JsonNode order = transport.request("GET", "orders/" + identifier(orderId), null).path("order");
        if (!orderId.equals(order.path("id").asText()) || !location.equals(order.path("location_id").asText())
                || !("BB-" + checkoutId).equals(order.path("reference_id").asText())
                || !moneyMatches(order.path("total_money"), expectedTotal)) {
            throw new IllegalArgumentException("Square order does not match this invoice checkout. No payment was recorded.");
        }
        if (!"COMPLETED".equals(order.path("state").asText())) {
            return null;
        }
        JsonNode tenders = order.path("tenders");
        if (tenders.size() != 1) {
            throw new IllegalArgumentException("The completed Square order has multiple or missing tenders and requires accounting review.");
        }
        String paymentId = identifier(tenders.get(0).path("payment_id").asText(tenders.get(0).path("id").asText()));
        JsonNode payment = transport.request("GET", "payments/" + paymentId, null).path("payment");
        if (!paymentId.equals(payment.path("id").asText()) || !orderId.equals(payment.path("order_id").asText())
                || !location.equals(payment.path("location_id").asText()) || !"COMPLETED".equals(payment.path("status").asText())
                || !moneyMatches(payment.path("total_money"), expectedTotal)
                || payment.path("refunded_money").path("amount").asLong() != 0) {
            throw new IllegalArgumentException("Square has not verified an exact, completed, unrefunded payment for this invoice.");
        }
        long processingFee = 0;
        for (JsonNode fee : payment.path("processing_fee")) {
            if (!"USD".equals(fee.path("amount_money").path("currency").asText())) {
                throw new IllegalArgumentException("Square processing fee currency does not match.");
            }
            processingFee = Math.addExact(processingFee, fee.path("amount_money").path("amount").longValue());
        }
        return new Capture(paymentId, expectedTotal, "USD", processingFee);
    }

    private static boolean moneyMatches(JsonNode money, long expected) {
        return money.path("amount").isIntegralNumber() && money.path("amount").canConvertToLong()
                && money.path("amount").longValue() == expected && "USD".equals(money.path("currency").asText());
    }

    private static String identifier(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,192}")) {
            throw new IllegalArgumentException("Square returned an invalid reference.");
        }
        return id;
    }
}
