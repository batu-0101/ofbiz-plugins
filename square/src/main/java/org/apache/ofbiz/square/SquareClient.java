/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.square;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Read-only account connection. Credentials and raw provider responses never enter OFBiz records. */
public final class SquareClient {
    private static final String API_VERSION = "2026-09-16";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Transport transport;

    interface Transport {
        Reply get(String path, String token) throws IOException, InterruptedException;
    }

    record Reply(int status, String body) { }

    public SquareClient() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        transport = (path, token) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://connect.squareup.com/v2/" + path))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                    .header("Square-Version", API_VERSION).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Reply(response.statusCode(), response.body());
        };
    }

    SquareClient(Transport transport) {
        this.transport = transport;
    }

    public Map<String, Object> readAccount(String token, String expectedMerchantId) {
        if (token == null || token.isBlank()) {
            return failed("Square is not connected. The production token is missing from Windows Credential Manager.");
        }
        if (expectedMerchantId == null || expectedMerchantId.isBlank()) {
            return failed("The expected Square account has not been configured.");
        }
        try {
            JsonNode merchant = get("merchants/me", token).path("merchant");
            if (!expectedMerchantId.equals(merchant.path("id").asText())) {
                return failed("The credential belongs to a different Square account. The connection was not opened.");
            }
            JsonNode rawLocations = get("locations", token).path("locations");
            if (!rawLocations.isArray()) {
                return failed("Square returned an incomplete location response. Refresh to retry.");
            }
            List<Map<String, Object>> locations = new ArrayList<>();
            for (JsonNode location : rawLocations) {
                if (!expectedMerchantId.equals(location.path("merchant_id").asText())) {
                    return failed("Square returned a location outside the connected account.");
                }
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("id", required(location, "id"));
                summary.put("name", required(location, "name"));
                summary.put("status", required(location, "status"));
                summary.put("currency", required(location, "currency"));
                boolean cards = false;
                for (JsonNode capability : location.path("capabilities")) {
                    cards |= "CREDIT_CARD_PROCESSING".equals(capability.asText());
                }
                summary.put("cardProcessing", cards);
                locations.add(summary);
            }
            Map<String, String> account = Map.of("id", expectedMerchantId, "name", required(merchant, "business_name"),
                    "status", required(merchant, "status"), "country", required(merchant, "country"),
                    "currency", required(merchant, "currency"));
            return Map.of("connected", true, "merchant", account, "locations", locations, "checkedAt", Instant.now().toString());
        } catch (SquareFailure e) {
            return failed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("The Square connection check was interrupted. Refresh to retry.");
        } catch (IOException | IllegalArgumentException e) {
            // Provider bodies and exception details can contain private values. Never display or log them.
            return failed("Square could not be reached or returned an invalid response. Refresh to retry.");
        }
    }

    private JsonNode get(String path, String token) throws IOException, InterruptedException, SquareFailure {
        Reply reply = transport.get(path, token);
        if (reply.status() == 401 || reply.status() == 403) {
            throw new SquareFailure("Square rejected the connection credential or its account-read permission.");
        }
        if (reply.status() == 429) {
            throw new SquareFailure("Square is limiting requests. Wait a moment, then refresh.");
        }
        if (reply.status() != 200) {
            throw new SquareFailure("Square is unavailable (HTTP " + reply.status() + "). Refresh to retry.");
        }
        JsonNode body = JSON.readTree(reply.body());
        if (body == null || !body.isObject() || body.path("errors").size() > 0) {
            throw new SquareFailure("Square returned an unsuccessful response. Refresh to retry.");
        }
        return body;
    }

    private static String required(JsonNode object, String field) {
        String value = object.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Incomplete Square response");
        }
        return value;
    }

    private static Map<String, Object> failed(String message) {
        return Map.of("connected", false, "message", message);
    }

    private static final class SquareFailure extends Exception {
        private static final long serialVersionUID = 1L;

        private SquareFailure(String message) {
            super(message);
        }
    }
}
