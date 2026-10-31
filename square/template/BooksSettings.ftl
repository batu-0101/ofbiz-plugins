<div class="screenlet"><div class="screenlet-title-bar"><h1>Square invoice payment settings</h1></div><div class="screenlet-body">
  <#if requestAttributes.booksError?has_content><p class="errorMessage" role="alert">${requestAttributes.booksError}</p></#if>
  <#if requestAttributes.booksSuccess?has_content><p class="eventMessage">${requestAttributes.booksSuccess}</p></#if>
  <#if requestAttributes.booksOrganizations??>
    <p>Connected Square account: <strong>Home &amp; Beyond Services</strong>. Bind only its matching OFBiz organization. Fees are disclosed before checkout and kept separate from invoice principal.</p>
    <form action="<@ofbizUrl>booksSettings</@ofbizUrl>" method="get">
      <p><label>Issuing organization <select name="partyId" required><option value="">Select organization</option><#list requestAttributes.booksOrganizations as org><option value="${org.partyId}"<#if org.partyId == requestAttributes.booksSelectedParty> selected</#if>>${org.name} (${org.partyId})<#if org.enabled == "true"> — Payments enabled</#if></option></#list></select></label> <button type="submit">View settings</button></p>
    </form>
    <#if requestAttributes.booksSelectedParty?has_content>
    <p data-payment-enabled="${requestAttributes.booksPaymentEnabled?c}"><strong><#if requestAttributes.booksPaymentEnabled>Square payment processing enabled<#else>Square payment processing not enabled</#if></strong> for ${requestAttributes.booksSelectedParty}.</p>
    <#if requestAttributes.booksPaymentEnabled><p>USD card checkout uses the connected Square location. After a customer pays, use Refresh Square payment status on the invoice to verify and record the payment.</p></#if>
    <form action="<@ofbizUrl>booksSettings</@ofbizUrl>" method="post">
      <input type="hidden" name="booksCsrf" value="${requestAttributes.booksCsrf}"/>
      <input type="hidden" name="partyId" value="${requestAttributes.booksSelectedParty}"/>
      <p><label><input type="checkbox" name="feeEnabled" value="true"<#if requestAttributes.booksFeeEnabled> checked</#if>/> Add the disclosed card processing fee</label></p>
      <p><label>Percentage in basis points (300 = 3%) <input name="feeBps" type="number" min="0" max="2000" step="1" value="${requestAttributes.booksFeeBps}" required/></label></p>
      <p><label>Fixed fee in cents (30 = $0.30) <input name="feeFixedCents" type="number" min="0" max="1000" step="1" value="${requestAttributes.booksFeeFixed}" required/></label></p>
      <p><label><input type="checkbox" name="confirmMerchant" value="true" required/> This issuing organization is Home &amp; Beyond Services, the connected Square merchant.</label></p>
      <button type="submit">Save Square payment settings</button>
    </form>
    </#if>
  </#if>
</div></div>
