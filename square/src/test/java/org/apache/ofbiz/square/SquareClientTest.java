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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SquareClientTest {
    private static final String TEST_TOKEN = "synthetic-test-value";
    private static final String MERCHANT = "{\"merchant\":{\"id\":\"M1\",\"business_name\":\"Test merchant\","
            + "\"status\":\"ACTIVE\",\"country\":\"US\",\"currency\":\"USD\"}}";
    private static final String LOCATIONS = "{\"locations\":[{\"id\":\"L1\",\"merchant_id\":\"M1\",\"name\":\"Test location\","
            + "\"status\":\"ACTIVE\",\"currency\":\"USD\",\"capabilities\":[\"CREDIT_CARD_PROCESSING\"],"
            + "\"address\":{\"address_line_1\":\"private-value\"}}]}";

    @Test
    public void connectsUsingOnlyAccountReadsAndWhitelistsReturnedFields() {
        List<String> calls = new ArrayList<>();
        SquareClient client = new SquareClient((path, token) -> {
            calls.add(path);
            assertEquals(TEST_TOKEN, token);
            return new SquareClient.Reply(200, "merchants/me".equals(path) ? MERCHANT : LOCATIONS);
        });
        Map<String, Object> result = client.readAccount(TEST_TOKEN, "M1");
        assertEquals(true, result.get("connected"));
        assertEquals(List.of("merchants/me", "locations"), calls);
        assertFalse(result.toString().contains(TEST_TOKEN));
        assertFalse(result.toString().contains("private-value"));
        assertTrue(result.toString().contains("cardProcessing=true"));
    }

    @Test
    public void missingCredentialNeverCallsProvider() {
        SquareClient client = new SquareClient((path, token) -> {
            throw new AssertionError("Must not call Square");
        });
        assertEquals(false, client.readAccount(null, "M1").get("connected"));
        assertEquals(false, client.readAccount(" ", "M1").get("connected"));
    }

    @Test
    public void missingAccountPinNeverCallsProvider() {
        SquareClient client = new SquareClient((path, token) -> {
            throw new AssertionError("Must not call Square");
        });
        assertEquals(false, client.readAccount(TEST_TOKEN, "").get("connected"));
    }

    @Test
    public void credentialForDifferentMerchantDoesNotReadItsLocations() {
        List<String> calls = new ArrayList<>();
        SquareClient client = new SquareClient((path, token) -> {
            calls.add(path);
            return new SquareClient.Reply(200, MERCHANT);
        });
        assertEquals(false, client.readAccount(TEST_TOKEN, "OTHER").get("connected"));
        assertEquals(List.of("merchants/me"), calls);
    }

    @Test
    public void locationForDifferentMerchantIsRejected() {
        SquareClient client = new SquareClient((path, token) -> new SquareClient.Reply(200,
                "merchants/me".equals(path) ? MERCHANT : LOCATIONS.replace("M1", "OTHER")));
        Map<String, Object> result = client.readAccount(TEST_TOKEN, "M1");
        assertEquals(false, result.get("connected"));
        assertFalse(result.containsKey("locations"));
    }

    @Test
    public void providerErrorsAndRedirectsCannotReportConnectedOrExposeBodies() {
        for (int status : List.of(301, 401, 403, 429, 500)) {
            SquareClient client = new SquareClient((path, token) -> new SquareClient.Reply(status, TEST_TOKEN));
            Map<String, Object> result = client.readAccount(TEST_TOKEN, "M1");
            assertEquals(false, result.get("connected"));
            assertFalse(result.toString().contains(TEST_TOKEN));
        }
    }

    @Test
    public void failureOnSecondRequestDoesNotShowPartialSuccess() {
        SquareClient client = new SquareClient((path, token) -> "merchants/me".equals(path)
                ? new SquareClient.Reply(200, MERCHANT) : new SquareClient.Reply(403, TEST_TOKEN));
        Map<String, Object> result = client.readAccount(TEST_TOKEN, "M1");
        assertEquals(false, result.get("connected"));
        assertFalse(result.containsKey("merchant"));
    }

    @Test
    public void malformedAndIncompleteResponsesFailClosed() {
        for (String body : List.of("{invalid-json", "null", "{}", "{\"errors\":[{}]}")) {
            SquareClient client = new SquareClient((path, token) -> new SquareClient.Reply(200, body));
            assertEquals(false, client.readAccount(TEST_TOKEN, "M1").get("connected"));
        }
        SquareClient incomplete = new SquareClient((path, token) -> new SquareClient.Reply(200,
                "merchants/me".equals(path) ? MERCHANT : "{}"));
        assertEquals(false, incomplete.readAccount(TEST_TOKEN, "M1").get("connected"));
    }

    @Test
    public void networkErrorsDoNotExposeExceptionDetails() {
        SquareClient client = new SquareClient((path, token) -> {
            throw new IOException(TEST_TOKEN);
        });
        Map<String, Object> result = client.readAccount(TEST_TOKEN, "M1");
        assertEquals(false, result.get("connected"));
        assertFalse(result.toString().contains(TEST_TOKEN));
    }
}
