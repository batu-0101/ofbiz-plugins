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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.ofbiz.security.Security;
import org.junit.Test;

public class SquareConnectionEventsTest {
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    @Test
    public void anonymousRequestIsForbiddenBeforeProviderAccess() {
        assertDenied(null);
    }

    @Test
    public void signedInUserWithoutAccountingAdminIsForbidden() {
        HttpSession session = proxy(HttpSession.class, (object, method, args) ->
                "getAttribute".equals(method.getName()) ? new Object() : null);
        assertDenied(session);
    }

    private void assertDenied(HttpSession session) {
        Map<String, Object> attributes = new HashMap<>();
        Security security = proxy(Security.class, (object, method, args) -> {
            assertEquals("hasEntityPermission", method.getName());
            assertEquals("ACCOUNTING", args[0]);
            assertEquals("_ADMIN", args[1]);
            return false;
        });
        attributes.put("security", security);
        HttpServletRequest request = proxy(HttpServletRequest.class, (object, method, args) -> {
            if ("getSession".equals(method.getName())) {
                return session;
            }
            if ("getAttribute".equals(method.getName())) {
                return attributes.get(args[0]);
            }
            if ("setAttribute".equals(method.getName())) {
                attributes.put((String) args[0], args[1]);
            }
            return null;
        });
        AtomicInteger status = new AtomicInteger(200);
        Map<String, String> headers = new HashMap<>();
        HttpServletResponse response = proxy(HttpServletResponse.class, (object, method, args) -> {
            if ("setStatus".equals(method.getName())) {
                status.set((Integer) args[0]);
            }
            if ("setHeader".equals(method.getName())) {
                headers.put((String) args[0], (String) args[1]);
            }
            return null;
        });
        assertEquals("error", SquareConnectionEvents.load(request, response));
        assertEquals(403, status.get());
        assertEquals("no-store", headers.get("Cache-Control"));
        assertFalse(attributes.containsKey("squareConnection"));
    }
}
