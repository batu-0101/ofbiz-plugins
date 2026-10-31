# Beyond Books estimate and Pay Now workflow

Installed in the running OFBiz on 2026-09-17. Open [Estimates and invoices](https://localhost:8443/square/control/books). Native quote and invoice pages also contain review/payment links.

The reference repository was read only: `D:/BDL/DEV-REPO/beyond-books`, commit `0ed1aef5ae90fdaee7737823508bea1f4ebe5490`. This work belongs to OFBiz and its nested plugins repository; the archived Beyond Books application was not changed or restarted.

## Behavior copied and adapted

| Beyond Books source | OFBiz behavior |
| --- | --- |
| `main/domain/approvalLinks.ts`, `clientApproveByToken`; `main/domain/estimates.ts`, `convertEstimateToInvoice` | An issued native quote (`QUO_APPROVED`) becomes one native ready sales invoice in one database transaction. The original quote URL then shows the invoice, number and approval timestamp. Repeated approval returns the same invoice. |
| Estimate line, note and payment-term copy | Native quantities, unit prices, descriptions, product references, finalized mapped adjustments, currency and terms are copied. The native invoice total must equal the reviewed estimate total or the entire conversion rolls back. |
| Stale approval checks | Expired, future-dated, draft, rejected and already-ordered quotes cannot convert. A digest binds approval to the reviewed header, lines, adjustments and terms. Converted quotes cannot be edited or ordered through the guarded native services. |
| `main/portal/portalHtml.ts` processing-fee modal | Pay Now opens an accessible review dialog showing principal, processing fee, total, fee-free check alternative, Agree and continue, and Back. With no fee, it states that no processing fee is added. |
| `shared/paymentPlan.ts`, `cardSurcharge`; `main/domain/cardFeeAcknowledgements.ts` | Server-side basis-point percentage, half-up cent rounding and fixed cents. Exact displayed principal/fee/total and explicit consent are revalidated. Changed amounts require new review. Consent is recorded with actor, timestamp and notice version. |
| `main/domain/cardCheckouts.ts`; `main/integrations/square.ts` | Opening/accepting checkout creates no payment and changes no invoice balance. Square hosted checkout uses the accepted amounts and a persistent unique provider request identity. Authenticated order and payment reads must verify location, reference, currency, total and completion before posting. |

OFBiz's native `BigDecimal` invoice/payment model is retained; integer cents are used at the fee/provider boundary. There is no parallel invoice ledger. Captured principal applies only to the original invoice. A positive accepted fee becomes a separate native fee invoice, and the gross native payment applies exactly across the two. Failed application rolls the complete local posting back. Captures against an unpayable/insufficient-balance invoice are held for review instead of overpaying it.

Native percentage installment terms determine the next unpaid amount. This local Square workflow supports USD. Approval is available to the authenticated quote customer and accounting administrators; it uses existing OFBiz login and party ownership. Public magic links, email delivery, public hosting, historical imports, refund synchronization and automatic background reconciliation were not added. Use Refresh Square payment status after a hosted payment. A browser return alone never marks an invoice paid.

## Current production configuration

The connected production merchant remains **Home & Beyond Services**, `ML5VGNZPYK1E8`, location `LD5XGDRB6S7GX`. Credentials still come only from Windows Credential Manager through the existing launcher. No new credential store exists.

**Live charging remains gated:** the real issuing OFBiz organization has not been bound to that merchant, and the owner's active fee rate was not supplied. The normal fee default is disabled/zero. The synthetic verification organization used 3% + $0.30 solely to test disclosure; that is not a selected business rate.

Accounting administrators can use [Invoice payment settings](https://localhost:8443/square/control/booksSettings) to bind the correct internal organization and set the fee. Non-secret configuration is stored as that organization's `PartyAttribute` values: `BB_SQUARE_MERCHANT`, `BB_CARD_FEE_ENABLED`, `BB_CARD_FEE_BPS`, `BB_CARD_FEE_FIXED`. `config/books.properties` supplies non-secret defaults. Sample-company invoices cannot use the production merchant without an explicit binding.

## Data, transaction and access boundaries

- Three additive entities: `BbQuoteConversion` (one invoice per quote; invoice ID unique), `BbCheckout` (unique request identity and provider payment ID), `BbFeeConsent` (one immutable acceptance per checkout).
- Two seed rows: converted-quote status and its allowed transition. Existing data was not reset. Derby created the additive tables/columns through normal component schema initialization.
- Native quote/invoice row write locks serialize the relevant mutations. Database uniqueness protects replay. Native quote mutator ECAs preserve the approved source document.
- All mutation events require POST and a session CSRF value. Actor identity comes from the authenticated session, not submitted customer IDs. Service authorization precedes privileged native service calls.
- Each financial service runs in its own transaction when called from HTTP, so a refused request can render the intact document and review instruction after rollback. Provider request IDs are persisted before the external handoff, allowing retries to reuse the same Square request.
- Document views disable caching. Provider response bodies and credentials are never copied to HTML, evidence or logs. External TLS validation stays enabled.

## Verified result

- Gradle `test --tests org.apache.ofbiz.square.* checkstyleMain checkstyleTest jar`: **BUILD SUCCESSFUL**, exit 0; **23 unit tests, zero failures/errors**.
- OFBiz/Derby component suite: **13 tests, zero failures/errors**, including customer isolation, repeat approval, stale/expired/draft refusal, rollback after invoice creation, native edit protection, installment amount, concurrent fee clicks and principal/fee settlement separation. Fixtures roll back; the concurrent checkout fixture is explicitly cleaned up.
- **17 live HTTP checks passed** for the rendered workflow, POST/CSRF behavior, exact fee notice, changed/absent consent, repeat approval, original estimate URL, balance preservation, assets, cache headers and production-issuer gate.
- **5 further native-page/cleanup checks passed.** Test invoices `CI1`, `CI2`, `BB_WEB_FEE` were canceled through native OFBiz status services; their audit history remains. `BB_WEB_EST` and `BB_WEB_EST2` are labeled synthetic converted quotes. `BB_VERIFY` is a synthetic, unbound verification party, not a live merchant.
- The existing Square account connection was rechecked: **11 live connector checks passed**. No live payment, refund, payment-link creation or customer message was sent.
- JavaScript syntax check and both repository whitespace checks passed. The server is running on `127.0.0.1:8443` from the verified JAR.

Evidence files: `runtime/books-verification.json`, `runtime/books-native-verification.json`, `runtime/square-verification.json`, `runtime/logs/test-results/beyond-books-workflow.xml`, `runtime/books-build.log`, `runtime/books-integration.log`, `runtime/books-migration.log`. JSON evidence includes the running JAR hash without session keys or raw pages. Expected negative-test refusals appear in service logs; they are not reported as a zero-error-log run.

**Browser visual verification remains pending.** The browser tool stops at `ERR_CERT_AUTHORITY_INVALID` for the bundled localhost certificate. Its security-warning handoff rule requires the owner to accept that warning. No browser warning was bypassed. HTTP checks inspect the actual rendered pages, but do not claim a visual or browser-click test.

The HTTP fixture file and verification scripts are for a fresh, explicitly synthetic local test run. Do not reload `WebFixture.xml` over converted/canceled test records: use new test IDs for a later run. The financial service tests are independently repeatable and roll back their fixtures. Do not run load-all against an existing installation.

## Ownership and reversal

Application Java, controller, entities, templates, tests and settings belong to `D:/OFBiz/plugins/square` in the plugins repository. The two native page-link insertions and this installation's evidence belong to the framework repository. No commit or push was requested.

To disable new use while retaining audit/history, remove the two native page links and disable the Square component's workflow request mappings. Do not drop populated conversion/checkout/consent tables or reset financial records. Undo an issued test/real invoice through OFBiz's native cancellation/reversal workflow, not deletion.

Square contract references: [Create payment link](https://developer.squareup.com/reference/square/checkout/create-payment-link), [Checkout configuration](https://developer.squareup.com/docs/checkout-api/optional-checkout-configurations), [Checkout API](https://developer.squareup.com/docs/checkout-api). The existing API pin remains `2026-09-16`.
