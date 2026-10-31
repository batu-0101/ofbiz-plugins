# Square account connection

**Workflow extension, 2026-09-17:** [Beyond Books estimate conversion and Pay Now disclosure](BOOKS-WORKFLOW.md) are installed. The extension supports explicitly bound invoice checkout and authenticated capture reconciliation; live charging remains gated pending the real issuing organization and fee configuration. The account-read connection below remains active. Current combined verification: 23 unit tests, 13 Derby service tests, 22 workflow/native HTTP checks, and 11 live account-connection checks passed. No live charge was made.

Connected on 2026-09-17 at Batu's request: "Connect my square account."

Open https://localhost:8443/square/control/main, or choose **Square** in the OFBiz application sidebar. The page uses the Beyond Books theme and verifies the live account every time it opens or **Refresh connection** is selected.

## Connected account and scope

- Environment: Square production.
- Merchant: Home & Beyond Services, `ML5VGNZPYK1E8`, ACTIVE, US / USD.
- Active location: Home & Beyond Services, `LD5XGDRB6S7GX`, USD.
- Square reports credit-card-processing and automatic-transfer capabilities for that location. These are provider capabilities, not a claim that OFBiz can execute them through this connector.
- The connection page reads merchant and location information. The subsequent workflow extension is described above; historical imports, refunds, webhooks and automatic mapping into the demonstration ledger remain outside its scope.

There was no existing Square connector in the installed framework or official plugins. This component adds the persistent account connection and its live OFBiz screen. Payment execution and transaction synchronization are not implemented or claimed.

## Credential handling and access

The existing credential is read with the Windows `CredReadW` API from `BeyondDigitalLabs/providers/square/SQUARE_PRODUCTION_ACCESS_TOKEN`. The token was verified directly against Square before integration. It was not created, changed or printed.

`D:/OFBiz/Windows-CredentialVault.ps1` adds a read method restricted to that exact target. `Start-OFBiz.ps1` passes the value to Java in `OFBIZ_SQUARE_ACCESS_TOKEN`, then clears the parent process environment. No `.env`, token configuration file, database credential row or second vault was created. Restart with the normal launcher after rotating the stored token.

The public merchant ID in `config/square.properties` pins the account. A token for another merchant or a location for a different merchant causes the connection to fail closed. No last-known success is cached or presented as current.

The controller requires sign-in; the Java event and screen independently require `ACCOUNTING_ADMIN`. The event checks authorization before reading the token or contacting Square. Responses carry `Cache-Control: no-store, no-cache, must-revalidate, private`. Token values, provider error bodies and exception details are excluded from rendered messages. Only selected account/location fields enter the request context; addresses and other provider fields are omitted.

Outbound requests use fixed production HTTPS endpoints, normal TLS validation, no redirect following and bounded timeouts. There is no periodic watcher or scheduled task.

## Evidence

- Direct Square `GET /v2/merchants/me` and `GET /v2/locations` returned HTTP 200 with the account/location above.
- Gradle command: `gradlew.bat test --tests 'org.apache.ofbiz.square.*' checkstyleMain checkstyleTest jar --console=plain`, with repository-local `GRADLE_USER_HOME` and process `NODE_ENV=development`: **BUILD SUCCESSFUL**, exit 0. Log: `D:/OFBiz/runtime/square-build.log`.
- **11 tests, 0 failures, 0 errors**: successful reads and field filtering; missing token/account pin; mismatched merchant/location; provider failures and redirects; partial failure; malformed responses; exception redaction; anonymous and non-admin rejection.
- **11 live checks passed**, run by `Verify-Square.ps1`: anonymous HTTP 401 with no merchant data; authenticated HTTP 200; connected marker; correct merchant and location; refresh; Beyond Books theme; no-store response; no credential in either response; no embedded rendering errors.
- Evidence: `D:/OFBiz/runtime/square-verification.json`, including timestamp, JAR hash and test counts. It stores no token, cookie, CSRF value, session key or raw page body.
- Server restarted through the existing launcher, remains bound to 127.0.0.1:8443, and the final server stdout contains zero `|E|` entries.

During verification, OFBiz's view handler overwrote the event's cache header. Adding `no-cache="true"` to the view map corrected it; the final live header check passes. Three test-formatting errors were corrected before the successful style/build run. The browser certificate handoff from the theme task remains unresolved; this record claims server/API verification, not screenshot inspection.

For reproduction, run `Verify-Square.ps1 -Username <OFBiz-user> -Password <password>` in PowerShell 7. The test accepts the local development certificate only for localhost requests; Square's outbound certificate verification stays enabled.

## Files and ownership

This component belongs to the nested `D:/OFBiz/plugins` Git repository. The launcher/vault changes and runtime verification belong to the parent OFBiz repository. Preserve the earlier ecommerce theme and Scrum changes when committing. No commit or push was requested.

Public API contract references: [Square Merchants API](https://developer.squareup.com/docs/merchants-api), [List locations](https://developer.squareup.com/reference/square/locations/list-locations). Requests pin Square API version `2026-09-16`, verified by the successful production calls in this session.
