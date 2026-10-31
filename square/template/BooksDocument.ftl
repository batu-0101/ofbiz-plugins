<#assign booksError=requestAttributes.booksError!"" booksSuccess=requestAttributes.booksSuccess!"" booksCsrf=requestAttributes.booksCsrf!"">
<#if requestAttributes.booksDocument??><#assign booksDocument=requestAttributes.booksDocument></#if>
<link rel="stylesheet" href="/square/images/books.css"/>
<article id="books-document" class="books-document" aria-live="polite">
  <a href="<@ofbizUrl>books</@ofbizUrl>">Estimates &amp; invoices</a>
  <#if booksError?has_content><p class="errorMessage" role="alert">${booksError}</p></#if>
  <#if booksSuccess?has_content><p class="books-success" role="status">${booksSuccess}</p></#if>
  <#if requestAttributes.booksPaymentUrl?has_content><p><a class="buttontext" href="${requestAttributes.booksPaymentUrl}" target="_blank" rel="noopener noreferrer">Continue to secure Square payment</a></p><p>Return here after payment and refresh its status. Opening Square does not mark the invoice paid.</p></#if>
  <#if booksDocument??>
    <#assign doc=booksDocument>
    <header><p class="books-eyebrow" id="document-kind">${doc.kind}</p><h1 id="document-number">${doc.id}</h1>
      <#if doc.approvedAt??><p class="books-approved">Approved ${doc.approvedAt?string("MMM d, yyyy h:mm a")} · From estimate ${doc.estimateId}</p></#if>
      <p>${doc.description!}</p><p>Customer ${doc.partyId} · ${doc.currency}</p>
    </header>
    <table class="basic-table"><thead><tr><th>Description</th><th>Quantity</th><th>Unit price</th><th>Amount</th></tr></thead><tbody>
      <#list doc.lines as line><tr><td>${line.description!}</td><td>${line.quantity!1}</td><td>${line.amount?string["0.00####"]}</td><td>${line.lineTotal?string["0.00"]}</td></tr></#list>
    </tbody></table>
    <div class="books-totals"><p>Total <strong>${doc.currency} ${doc.total?string["0.00"]}</strong></p>
      <#if doc.balance??><p>Balance due <strong>${doc.currency} ${doc.balance?string["0.00"]}</strong></p></#if>
    </div>
    <#if doc.terms?has_content><h2>Payment terms</h2><ul><#list doc.terms as term><li>${term.description!term.termTypeId}<#if term.termValue??> · ${term.termValue}</#if><#if term.termDays??> · ${term.termDays} days</#if><#if term.textValue?has_content> · ${term.textValue}</#if></li></#list></ul></#if>
    <#if doc.kind=="Estimate">
      <#if doc.canApprove>
        <form method="post" action="<@ofbizUrl>approveEstimate</@ofbizUrl>" data-books-approve>
          <input type="hidden" name="quoteId" value="${doc.id}"/><input type="hidden" name="snapshotDigest" value="${doc.digest}"/>
          <input type="hidden" name="booksCsrf" value="${booksCsrf}"/>
          <p>By approving, you accept these line items, prices and terms. This estimate will become your invoice.</p>
          <button type="submit" class="books-primary">Approve estimate</button>
        </form>
      <#else><p>This estimate is not available for approval. The office must issue the current approved version first.</p></#if>
    <#elseif doc.principalCents??>
      <button type="button" class="books-primary" data-books-pay hidden>Pay now · ${doc.currency} ${doc.principal?string["0.00"]}</button>
      <dialog id="books-fee-dialog" open aria-labelledby="books-fee-title">
        <h2 id="books-fee-title"><#if doc.feeCents gt 0>Card processing fee<#else>Review your payment</#if></h2>
        <p>Review the total before continuing to secure payment.</p>
        <dl class="books-fee-breakdown"><dt>Invoice payment</dt><dd>${doc.currency} ${doc.principal?string["0.00"]}</dd>
          <dt>Processing fee</dt><dd>${doc.currency} ${doc.fee?string["0.00"]}</dd>
          <dt>Total charged</dt><dd><strong>${doc.currency} ${doc.cardTotal?string["0.00"]}</strong></dd></dl>
        <#if doc.feeCents gt 0><p>By continuing, you agree to add this processing fee to the card charge. You can choose check or contact the office instead. No card processing fee applies when paying by check.</p><#else><p>No processing fee is added to this payment.</p></#if>
        <p>Only the invoice payment reduces your invoice balance.</p>
        <form method="post" action="<@ofbizUrl>acceptCardFee</@ofbizUrl>">
          <input type="hidden" name="booksCsrf" value="${booksCsrf}"/><input type="hidden" name="invoiceId" value="${doc.id}"/>
          <input type="hidden" name="principalCents" value="${doc.principalCents?c}"/><input type="hidden" name="feeCents" value="${doc.feeCents?c}"/>
          <input type="hidden" name="totalCents" value="${doc.totalCents?c}"/><input type="hidden" name="acknowledged" value="true"/>
          <button type="submit" class="books-primary"><#if doc.feeCents gt 0>Agree &amp; continue<#else>Continue to payment</#if></button>
          <button type="button" data-books-back hidden>Back</button>
        </form>
      </dialog>
    <#else><p>${doc.paymentMessage!"There is no payment due."}</p></#if>
    <#if doc.checkouts?has_content><section><h2>Payment activity</h2>
      <#list doc.checkouts as checkout><div class="books-payment-record">
        <p>Checkout ${checkout.checkoutId} · ${checkout.status} · Invoice payment ${doc.currency} ${(checkout.principalCents / 100)?string["0.00"]} · Fee ${(checkout.feeCents / 100)?string["0.00"]} · Total ${(checkout.totalCents / 100)?string["0.00"]}</p>
        <#if checkout.paymentId?has_content><p>Payment ${checkout.paymentId} · Square reference ${checkout.providerPaymentId!}</p></#if>
        <#if checkout.reviewReason?has_content><p role="alert">${checkout.reviewReason}</p></#if>
        <#if checkout.providerOrderId?has_content && checkout.status=="LINKED"><form method="post" action="<@ofbizUrl>refreshPayment</@ofbizUrl>">
          <input type="hidden" name="booksCsrf" value="${booksCsrf}"/><input type="hidden" name="checkoutId" value="${checkout.checkoutId}"/><input type="hidden" name="invoiceId" value="${doc.id}"/>
          <button type="submit">Refresh Square payment status</button>
        </form></#if>
      </div></#list>
    </section></#if>
  </#if>
</article>
<script src="/square/images/books.js" defer></script>
