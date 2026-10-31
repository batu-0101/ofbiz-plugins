<#assign booksError=requestAttributes.booksError!"" booksQuotes=requestAttributes.booksQuotes![] booksInvoices=requestAttributes.booksInvoices![]>
<div class="screenlet">
  <div class="screenlet-title-bar"><h1>Estimates &amp; invoices</h1></div>
  <div class="screenlet-body">
    <p>Review an estimate, approve it into an invoice, and review the card total before payment.</p>
    <#if booksError?has_content><p class="errorMessage">${booksError}</p></#if>
    <h2>Estimates</h2>
    <table class="basic-table"><thead><tr><th>Estimate</th><th>Customer</th><th>Status</th></tr></thead><tbody>
      <#list booksQuotes![] as q><tr><td><a href="<@ofbizUrl>document?quoteId=${q.quoteId}</@ofbizUrl>">${q.quoteId} · ${q.quoteName!"Estimate"}</a></td><td>${q.partyId!}</td><td>${q.statusId!}</td></tr></#list>
    </tbody></table>
    <h2>Invoices</h2>
    <table class="basic-table"><thead><tr><th>Invoice</th><th>Customer</th><th>Status</th></tr></thead><tbody>
      <#list booksInvoices![] as inv><tr><td><a href="<@ofbizUrl>document?invoiceId=${inv.invoiceId}</@ofbizUrl>">${inv.invoiceId} · ${inv.description!"Invoice"}</a></td><td>${inv.partyId!}</td><td>${inv.statusId!}</td></tr></#list>
    </tbody></table>
  </div>
</div>
