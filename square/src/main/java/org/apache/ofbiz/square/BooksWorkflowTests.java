package org.apache.ofbiz.square;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;

import org.apache.ofbiz.accounting.invoice.InvoiceWorker;
import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.base.util.UtilMisc;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.DelegatorFactory;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.service.ServiceContainer;
import org.apache.ofbiz.service.ServiceUtil;
import org.apache.ofbiz.service.testtools.OFBizTestCase;

/** Real Derby/native-service tests. Every fixture and mutation rolls back at the end of the test. */
public final class BooksWorkflowTests extends OFBizTestCase {
    private boolean began;
    private String quoteId;
    private GenericValue actor;

    public BooksWorkflowTests(String name) {
        super(name);
    }

    @Override
    protected void setUp() throws Exception {
        // Use real transaction rollback, not the framework's additional operation-replay cleanup.
        setDelegator(DelegatorFactory.getDelegator("default"));
        setDispatcher(ServiceContainer.getLocalDispatcher("books-transaction-tests", getDelegator()));
        began = TransactionUtil.begin();
        actor = getUserLogin("admin");
        quoteId = getDelegator().getNextSeqId("Quote");
        getDelegator().create("Quote", UtilMisc.toMap("quoteId", quoteId, "quoteTypeId", "PRODUCT_QUOTE", "partyId", "DemoCustomer",
                "productStoreId", "9000", "currencyUomId", "USD", "statusId", "QUO_APPROVED", "issueDate", UtilDateTime.nowTimestamp(),
                "description", "Beyond Books rollback-only verification"));
        getDelegator().create("QuoteItem", UtilMisc.toMap("quoteId", quoteId, "quoteItemSeqId", "00001", "quantity", new BigDecimal("2.5"),
                "quoteUnitPrice", new BigDecimal("40.00"), "comments", "Surgical workflow verification"));
        getDelegator().create("QuoteTerm", UtilMisc.toMap("quoteId", quoteId, "quoteItemSeqId", "_NA_", "termTypeId", "FIN_PAYMENT_TERM",
                "termDays", 30L, "description", "Net 30"));
    }

    @Override
    protected void tearDown() throws Exception {
        TransactionUtil.rollback(began, "Rollback workflow test fixtures", null);
    }

    private Map<String, Object> approve() throws Exception {
        GenericValue q = from("Quote").where("quoteId", quoteId).queryOne();
        return getDispatcher().runSync("bbApproveEstimate", Map.of("quoteId", quoteId,
                "snapshotDigest", BooksServices.quote(getDelegator(), q).get("digest"), "userLogin", actor));
    }

    private String invoice() throws Exception {
        Map<String, Object> result = approve();
        assertFalse(ServiceUtil.getErrorMessage(result), ServiceUtil.isError(result));
        return (String) result.get("invoiceId");
    }

    public void testCopiesLinesTermsAndReturnsSameInvoiceOnRetry() throws Exception {
        String id = invoice();
        GenericValue inv = from("Invoice").where("invoiceId", id).queryOne();
        assertEquals("INVOICE_READY", inv.getString("statusId"));
        assertEquals("USD", inv.getString("currencyUomId"));
        assertEquals(0, new BigDecimal("100.00").compareTo(InvoiceWorker.getInvoiceTotal(inv)));
        GenericValue item = from("InvoiceItem").where("invoiceId", id).queryFirst();
        assertEquals(0, new BigDecimal("2.5").compareTo(item.getBigDecimal("quantity")));
        assertEquals("Surgical workflow verification", item.getString("description"));
        assertEquals(1, from("InvoiceTerm").where("invoiceId", id).queryCount());
        assertEquals(id, approve().get("invoiceId"));
        assertEquals(1, from("BbQuoteConversion").where("quoteId", quoteId).queryCount());
        assertEquals("QUO_BB_CONVERTED", from("Quote").where("quoteId", quoteId).queryOne().getString("statusId"));
    }

    public void testRejectsStaleReviewedSnapshot() throws Exception {
        GenericValue q = from("Quote").where("quoteId", quoteId).queryOne();
        String digest = (String) BooksServices.quote(getDelegator(), q).get("digest");
        GenericValue item = from("QuoteItem").where("quoteId", quoteId).queryFirst();
        item.set("quoteUnitPrice", new BigDecimal("50.00"));
        item.store();
        Map<String, Object> result = getDispatcher().runSync("bbApproveEstimate",
                Map.of("quoteId", quoteId, "snapshotDigest", digest, "userLogin", actor));
        assertTrue(ServiceUtil.isError(result));
        assertNull(result.get("invoiceId"));
    }

    public void testDraftCannotConvert() throws Exception {
        GenericValue q = from("Quote").where("quoteId", quoteId).queryOne();
        q.set("statusId", "QUO_CREATED");
        q.store();
        assertTrue(ServiceUtil.isError(approve()));
    }

    public void testExpiredCannotConvert() throws Exception {
        GenericValue q = from("Quote").where("quoteId", quoteId).queryOne();
        q.set("validThruDate", new Timestamp(System.currentTimeMillis() - 1000));
        q.store();
        assertTrue(ServiceUtil.isError(approve()));
    }

    public void testAnotherCustomerCannotApprove() throws Exception {
        actor = getUserLogin("DemoCustAgent");
        assertTrue(ServiceUtil.isError(approve()));
    }

    public void testOwningCustomerCanApproveWithoutAccountingAdmin() throws Exception {
        actor = getUserLogin("DemoCustomer");
        assertNotNull(invoice());
    }

    public void testHalfCreatedInvoiceRollsBackWhenCurrencyChanges() throws Exception {
        GenericValue party = from("Party").where("partyId", "DemoCustomer").queryOne();
        party.set("preferredCurrencyUomId", "EUR");
        party.store();
        assertTrue(ServiceUtil.isError(approve()));
        TransactionUtil.rollback(began, "Verify failed conversion rolled back", null);
        began = false;
        assertNull(from("BbQuoteConversion").where("quoteId", quoteId).queryOne());
        assertEquals(0, from("Invoice").where("referenceNumber", quoteId).queryCount());
    }

    public void testConsentIsIdempotentAndCreatesNoPaymentOrBalanceChange() throws Exception {
        String id = invoice();
        GenericValue inv = from("Invoice").where("invoiceId", id).queryOne();
        BooksMoney.Charge c = BooksServices.charge(getDelegator(), inv);
        long paymentCount = from("Payment").queryCount();
        Map<String, Object> args = UtilMisc.toMap("userLogin", actor, "invoiceId", id, "principalCents", c.principal(),
                "feeCents", c.fee(), "totalCents", c.total(), "acknowledged", true);
        Map<String, Object> result = getDispatcher().runSync("bbAcceptCardFee", args);
        assertFalse(ServiceUtil.getErrorMessage(result), ServiceUtil.isError(result));
        assertEquals(result.get("checkoutId"), getDispatcher().runSync("bbAcceptCardFee", args).get("checkoutId"));
        assertEquals(1, from("BbFeeConsent").where("checkoutId", result.get("checkoutId")).queryCount());
        assertEquals(paymentCount, from("Payment").queryCount());
        assertEquals(0, new BigDecimal("100.00").compareTo(InvoiceWorker.getInvoiceNotApplied(inv)));
    }

    public void testStaleConsentCannotCreateCheckout() throws Exception {
        String id = invoice();
        Map<String, Object> args = UtilMisc.toMap("userLogin", actor, "invoiceId", id, "principalCents", 9999L,
                "feeCents", 0L, "totalCents", 9999L, "acknowledged", true);
        assertTrue(ServiceUtil.isError(getDispatcher().runSync("bbAcceptCardFee", args)));
        TransactionUtil.rollback(began, "Verify rejected fee consent rolled back", null);
        began = false;
        assertEquals(0, from("BbCheckout").where("invoiceId", id).queryCount());
    }

    public void testConvertedQuoteCannotBeEditedThroughNativeService() throws Exception {
        invoice();
        Map<String, Object> result = getDispatcher().runSync("updateQuoteItem", UtilMisc.toMap("userLogin", actor, "quoteId", quoteId,
                "quoteItemSeqId", "00001", "quoteUnitPrice", new BigDecimal("900.00")));
        assertTrue(ServiceUtil.isError(result));
    }

    public void testInstallmentUsesNextUnpaidNativeTerm() throws Exception {
        getDelegator().create("QuoteTerm", UtilMisc.toMap("quoteId", quoteId, "quoteItemSeqId", "_NA_",
                "termTypeId", "FIN_PAY_NETDAYS_1", "termValue", 25L, "termDays", 0L, "description", "Deposit"));
        String id = invoice();
        assertEquals(2500L, BooksServices.charge(getDelegator(), from("Invoice").where("invoiceId", id).queryOne()).principal());
    }

    public void testVerifiedCaptureAppliesPrincipalOnlyAndBooksFeeSeparately() throws Exception {
        String id = invoice();
        GenericValue inv = from("Invoice").where("invoiceId", id).queryOne();
        for (Map.Entry<String, String> entry : Map.of("BB_CARD_FEE_ENABLED", "true", "BB_CARD_FEE_BPS", "300",
                "BB_CARD_FEE_FIXED", "30").entrySet()) {
            getDelegator().createOrStore(getDelegator().makeValue("PartyAttribute", Map.of("partyId", inv.getString("partyIdFrom"),
                    "attrName", entry.getKey(), "attrValue", entry.getValue())));
        }
        Map<String, Object> result = getDispatcher().runSync("bbAcceptCardFee", UtilMisc.toMap("userLogin", actor, "invoiceId", id,
                "principalCents", 10000L, "feeCents", 330L, "totalCents", 10330L, "acknowledged", true));
        assertFalse(ServiceUtil.getErrorMessage(result), ServiceUtil.isError(result));
        GenericValue checkout = from("BbCheckout").where("checkoutId", result.get("checkoutId")).queryOne();
        SquareCheckoutClient.Capture capture = new SquareCheckoutClient.Capture("SYNTHETIC_" + quoteId, 10330, "USD", 321);
        BooksPaymentServices.settleVerified(getDispatcher().getDispatchContext(), checkout, inv, capture);
        assertEquals("PAID", checkout.getString("status"));
        assertEquals(0, new BigDecimal("100.00").compareTo(InvoiceWorker.getInvoiceApplied(inv)));
        assertEquals(0, new BigDecimal("100.00").compareTo(InvoiceWorker.getInvoiceTotal(inv)));
        GenericValue payment = from("Payment").where("paymentId", checkout.getString("paymentId")).queryOne();
        assertEquals(0, new BigDecimal("103.30").compareTo(payment.getBigDecimal("amount")));
        assertEquals(2, from("PaymentApplication").where("paymentId", payment.getString("paymentId")).queryCount());
        long count = from("Payment").queryCount();
        BooksPaymentServices.settleVerified(getDispatcher().getDispatchContext(), checkout, inv, capture);
        assertEquals(count, from("Payment").queryCount());
    }

    public void testHbsIssuerPostsInvoiceAndCardReceiptToItsOwnLedger() throws Exception {
        GenericValue q = from("Quote").where("quoteId", quoteId).queryOne();
        q.set("productStoreId", "HBS_SQUARE");
        q.store();
        String id = invoice();
        GenericValue inv = from("Invoice").where("invoiceId", id).queryOne();
        assertEquals("HBS_SQUARE", inv.getString("partyIdFrom"));
        assertEquals(0L, BooksServices.charge(getDelegator(), inv).fee());
        Map<String, Object> settings = getDispatcher().runSync("bbSavePaymentSettings", Map.of("partyId", "HBS_SQUARE",
                "feeEnabled", false, "feeBps", 0L, "feeFixedCents", 0L, "userLogin", actor));
        assertFalse(ServiceUtil.getErrorMessage(settings), ServiceUtil.isError(settings));
        BooksPaymentServices.merchant(inv);
        GenericValue sampleInvoice = (GenericValue) inv.clone();
        sampleInvoice.set("partyIdFrom", "Company");
        try {
            BooksPaymentServices.merchant(sampleInvoice);
            fail("The sample organization must remain unbound.");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not enabled"));
        }
        Map<String, Object> accepted = getDispatcher().runSync("bbAcceptCardFee", UtilMisc.toMap("userLogin", actor,
                "invoiceId", id, "principalCents", 10000L, "feeCents", 0L, "totalCents", 10000L, "acknowledged", true));
        assertFalse(ServiceUtil.getErrorMessage(accepted), ServiceUtil.isError(accepted));
        GenericValue checkout = from("BbCheckout").where("checkoutId", accepted.get("checkoutId")).queryOne();
        BooksPaymentServices.settleVerified(getDispatcher().getDispatchContext(), checkout, inv,
                new SquareCheckoutClient.Capture("SYNTHETIC_HBS_" + quoteId, 10000, "USD", 300));
        assertEquals(0, BigDecimal.ZERO.compareTo(InvoiceWorker.getInvoiceNotApplied(inv)));
        GenericValue payment = from("Payment").where("paymentId", checkout.getString("paymentId")).queryOne();
        assertEquals("HBS_SQUARE", payment.getString("partyIdTo"));
        var transactions = from("AcctgTrans").where("paymentId", payment.getString("paymentId")).queryList();
        assertFalse("Native payment must create ledger transactions", transactions.isEmpty());
        boolean clearing = false;
        for (GenericValue transaction : transactions) {
            assertEquals("Y", transaction.getString("isPosted"));
            BigDecimal net = BigDecimal.ZERO;
            for (GenericValue entry : from("AcctgTransEntry").where("acctgTransId", transaction.getString("acctgTransId")).queryList()) {
                assertEquals("HBS_SQUARE", entry.getString("organizationPartyId"));
                assertNotNull(entry.getString("glAccountId"));
                net = net.add(entry.getBigDecimal("amount").multiply("D".equals(entry.getString("debitCreditFlag"))
                        ? BigDecimal.ONE : BigDecimal.ONE.negate()));
                if ("HBS_SQUARE_CLEAR".equals(entry.getString("glAccountId"))) {
                    clearing = true;
                    assertEquals("D", entry.getString("debitCreditFlag"));
                    assertEquals(0, new BigDecimal("100.00").compareTo(entry.getBigDecimal("amount")));
                }
            }
            assertEquals(0, BigDecimal.ZERO.compareTo(net));
        }
        assertTrue("The gross receipt must debit Square clearing", clearing);
    }

    public void testConcurrentFeeClicksCreateOneCheckout() throws Exception {
        TransactionUtil.rollback(began, "Discard unused estimate fixture for concurrent checkout test", null);
        began = false;
        String id = "BBCT" + java.util.UUID.randomUUID().toString().substring(0, 12);
        getDelegator().create("Invoice", Map.of("invoiceId", id, "invoiceTypeId", "SALES_INVOICE", "partyId", "DemoCustomer",
                "partyIdFrom", "Company", "currencyUomId", "USD", "statusId", "INVOICE_READY"));
        getDelegator().create("InvoiceItem", Map.of("invoiceId", id, "invoiceItemSeqId", "00001", "invoiceItemTypeId", "INV_FPROD_ITEM",
                "quantity", BigDecimal.ONE, "amount", new BigDecimal("100.00")));
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            GenericValue inv = from("Invoice").where("invoiceId", id).queryOne();
            BooksMoney.Charge c = BooksServices.charge(getDelegator(), inv);
            Map<String, Object> args = UtilMisc.toMap("userLogin", actor, "invoiceId", id, "principalCents", c.principal(),
                    "feeCents", c.fee(), "totalCents", c.total(), "acknowledged", true);
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Map<String, Object>> click = () -> {
                start.await();
                return getDispatcher().runSync("bbAcceptCardFee", args);
            };
            var first = workers.submit(click);
            var second = workers.submit(click);
            start.countDown();
            Map<String, Object> a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
            Map<String, Object> b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(ServiceUtil.getErrorMessage(a), ServiceUtil.isError(a));
            assertFalse(ServiceUtil.getErrorMessage(b), ServiceUtil.isError(b));
            assertEquals(a.get("checkoutId"), b.get("checkoutId"));
            assertEquals(1, from("BbCheckout").where("invoiceId", id).queryCount());
            assertEquals(0, from("PaymentApplication").where("invoiceId", id).queryCount());
        } finally {
            workers.shutdown();
            assertTrue(workers.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS));
            for (GenericValue c : from("BbCheckout").where("invoiceId", id).queryList()) {
                getDelegator().removeByAnd("BbFeeConsent", Map.of("checkoutId", c.getString("checkoutId")));
                c.remove();
            }
            getDelegator().removeByAnd("InvoiceItem", Map.of("invoiceId", id));
            getDelegator().removeByAnd("Invoice", Map.of("invoiceId", id));
        }
    }
}
