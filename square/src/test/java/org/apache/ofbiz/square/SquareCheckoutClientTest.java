package org.apache.ofbiz.square;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;

public class SquareCheckoutClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode order() {
        return JSON.valueToTree(Map.of("order", Map.of("id", "ORDER1", "location_id", "LOC1", "reference_id", "BB-CO1",
                "state", "COMPLETED", "total_money", Map.of("amount", 10330, "currency", "USD"),
                "tenders", List.of(Map.of("payment_id", "PAY1")))));
    }

    private static ObjectNode payment() {
        return JSON.valueToTree(Map.of("payment", Map.of("id", "PAY1", "order_id", "ORDER1", "location_id", "LOC1",
                "status", "COMPLETED", "total_money", Map.of("amount", 10330, "currency", "USD"),
                "processing_fee", List.of(Map.of("amount_money", Map.of("amount", 301, "currency", "USD"))))));
    }

    @Test
    public void hostedCheckoutUsesExactAcceptedAmountsAndStableIdempotency() throws Exception {
        List<Map<String, Object>> requests = new ArrayList<>();
        SquareCheckoutClient client = new SquareCheckoutClient((method, path, body) -> {
            assertEquals("POST", method);
            assertEquals("online-checkout/payment-links", path);
            requests.add(body);
            return JSON.valueToTree(Map.of("payment_link", Map.of("id", "LINK1", "order_id", "ORDER1", "url", "https://square.link/u/TEST")));
        });
        BooksMoney.Charge charge = new BooksMoney.Charge(10000, 330, 10330);
        client.create("CO1", "REQUEST1", "INV1", "LOC1", charge);
        client.create("CO1", "REQUEST1", "INV1", "LOC1", charge);
        assertEquals(requests.get(0), requests.get(1));
        var body = JSON.valueToTree(requests.get(0));
        assertEquals(10000, body.path("order").path("line_items").get(0).path("base_price_money").path("amount").asLong());
        assertEquals(330, body.path("order").path("line_items").get(1).path("base_price_money").path("amount").asLong());
        assertTrue(body.path("checkout_options").has("allow_tipping"));
        assertEquals(false, body.path("checkout_options").path("allow_tipping").asBoolean());
    }

    @Test
    public void untrustedOrIncompleteCheckoutUrlsAreRejected() {
        for (String url : List.of("https://evil.example/", "http://square.link/x", "https://square.link@evil.example/x", "")) {
            SquareCheckoutClient client = new SquareCheckoutClient((method, path, body) -> JSON.valueToTree(
                    Map.of("payment_link", Map.of("id", "LINK1", "order_id", "ORDER1", "url", url))));
            assertThrows(IllegalArgumentException.class, () -> client.create("CO1", "REQUEST1", "INV1", "LOC1", new BooksMoney.Charge(100, 0, 100)));
        }
    }

    @Test
    public void captureRequiresAuthenticatedOrderAndPaymentReads() throws Exception {
        List<String> reads = new ArrayList<>();
        SquareCheckoutClient client = new SquareCheckoutClient((method, path, body) -> {
            assertEquals("GET", method);
            reads.add(path);
            return path.startsWith("orders/") ? order() : payment();
        });
        assertEquals(new SquareCheckoutClient.Capture("PAY1", 10330, "USD", 301), client.capture("CO1", "ORDER1", "LOC1", 10330));
        assertEquals(List.of("orders/ORDER1", "payments/PAY1"), reads);
    }

    @Test
    public void pendingOrderNeverBecomesPayment() throws Exception {
        ObjectNode pending = order();
        ObjectNode pendingOrder = (ObjectNode) pending.path("order");
        pendingOrder.put("state", "OPEN");
        SquareCheckoutClient client = new SquareCheckoutClient((method, path, body) -> {
            assertTrue(path.startsWith("orders/"));
            return pending;
        });
        assertNull(client.capture("CO1", "ORDER1", "LOC1", 10330));
    }

    @Test
    public void mismatchedOrderIdentityAndMoneyCannotPost() {
        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("id", "OTHER");
        mutations.put("reference_id", "BB-OTHER");
        mutations.put("location_id", "OTHER");
        for (Map.Entry<String, String> mutation : mutations.entrySet()) {
            ObjectNode wrong = order();
            ObjectNode wrongOrder = (ObjectNode) wrong.path("order");
            wrongOrder.put(mutation.getKey(), mutation.getValue());
            SquareCheckoutClient client = new SquareCheckoutClient((method, path, body) -> wrong);
            assertThrows(IllegalArgumentException.class, () -> client.capture("CO1", "ORDER1", "LOC1", 10330));
        }
        SquareCheckoutClient client = new SquareCheckoutClient((method, path, body) -> order());
        assertThrows(IllegalArgumentException.class, () -> client.capture("CO1", "ORDER1", "LOC1", 10329));
    }

    @Test
    public void declinedRefundedAndWrongCurrencyPaymentsCannotPost() {
        for (String mutation : List.of("status", "order_id", "location_id", "amount", "currency", "refund")) {
            ObjectNode wrong = payment();
            ObjectNode p = (ObjectNode) wrong.path("payment");
            ObjectNode money = (ObjectNode) p.path("total_money");
            if ("amount".equals(mutation)) {
                money.put("amount", 10329);
            } else if ("currency".equals(mutation)) {
                money.put("currency", "EUR");
            } else if ("refund".equals(mutation)) {
                p.set("refunded_money", JSON.valueToTree(Map.of("amount", 1, "currency", "USD")));
            } else {
                p.put(mutation, "WRONG");
            }
            SquareCheckoutClient client = new SquareCheckoutClient((method, path, body) -> path.startsWith("orders/") ? order() : wrong);
            assertThrows(IllegalArgumentException.class, () -> client.capture("CO1", "ORDER1", "LOC1", 10330));
        }
    }

    @Test
    public void locationMustBelongToExpectedActiveMerchant() throws Exception {
        SquareCheckoutClient client = new SquareCheckoutClient((method, path, body) -> JSON.valueToTree(Map.of("location",
                Map.of("id", "LOC1", "merchant_id", "MERCHANT1", "status", "ACTIVE", "currency", "USD"))));
        client.verifyLocation("LOC1", "MERCHANT1");
        assertThrows(IllegalArgumentException.class, () -> client.verifyLocation("LOC1", "OTHER"));
        assertThrows(IllegalArgumentException.class, () -> client.verifyLocation("../merchant", "MERCHANT1"));
    }
}
