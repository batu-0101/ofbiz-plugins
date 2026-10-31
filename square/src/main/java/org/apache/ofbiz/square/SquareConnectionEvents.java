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

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.security.Security;

/** Only accounting administrators can use the machine owner's Square connection. */
public final class SquareConnectionEvents {
    private static final SquareClient CLIENT = new SquareClient();

    private SquareConnectionEvents() { }

    public static String load(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        HttpSession session = request.getSession(false);
        Security security = (Security) request.getAttribute("security");
        if (session == null || session.getAttribute("userLogin") == null || security == null
                || !security.hasEntityPermission("ACCOUNTING", "_ADMIN", session)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "error";
        }
        request.setAttribute("squareConnection", CLIENT.readAccount(System.getenv("OFBIZ_SQUARE_ACCESS_TOKEN"),
                UtilProperties.getPropertyValue("square", "square.expectedMerchantId")));
        return "success";
    }
}
