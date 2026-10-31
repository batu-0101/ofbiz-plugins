<#assign booksExternalKey=requestAttributes.externalLoginKey!"">
<div class="button-bar">
  <#if quote??>
    <a class="buttontext" href="/square/control/document?quoteId=${quote.quoteId}&amp;externalLoginKey=${booksExternalKey}">Review estimate / invoice</a>
  <#elseif parameters.invoiceId?has_content>
    <a class="buttontext" href="/square/control/document?invoiceId=${parameters.invoiceId}&amp;externalLoginKey=${booksExternalKey}">Review invoice / Pay now</a>
  </#if>
</div>
