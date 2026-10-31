<#assign connection = requestAttributes.squareConnection!{}>
<div class="button-bar">
    <a class="buttontext" href="<@ofbizUrl>books</@ofbizUrl>">Estimates &amp; invoices</a>
    <a class="buttontext" href="<@ofbizUrl>booksSettings</@ofbizUrl>">Invoice payment settings</a>
    <a class="buttontext" href="<@ofbizUrl>main</@ofbizUrl>">Refresh connection</a>
    <a class="buttontext" href="https://app.squareup.com/" target="_blank" rel="noopener noreferrer">Open Square dashboard</a>
</div>
<#if connection.connected!false>
    <div class="eventMessage" role="status" data-square-status="connected">Square connected &middot; Production</div>
    <div class="screenlet">
        <div class="screenlet-title-bar"><h2>${connection.merchant.name}</h2></div>
        <div class="screenlet-body">
            <table class="basic-table" aria-label="Square account">
                <tbody>
                    <tr><td class="label">Account status</td><td>${connection.merchant.status}</td></tr>
                    <tr><td class="label">Country / currency</td><td>${connection.merchant.country} / ${connection.merchant.currency}</td></tr>
                    <tr><td class="label">Merchant ID</td><td>${connection.merchant.id}</td></tr>
                    <tr><td class="label">Verified at</td><td>${connection.checkedAt}</td></tr>
                </tbody>
            </table>
        </div>
    </div>
    <div class="screenlet">
        <div class="screenlet-title-bar"><h2>Square locations</h2></div>
        <div class="screenlet-body">
            <#if connection.locations?has_content>
                <table class="basic-table" aria-label="Square locations">
                    <thead><tr><th>Location</th><th>Status</th><th>Currency</th><th>Location ID</th><th>Card processing</th></tr></thead>
                    <tbody>
                        <#list connection.locations as location>
                            <tr><td>${location.name}</td><td>${location.status}</td><td>${location.currency}</td><td>${location.id}</td><td><#if location.cardProcessing>Available in Square<#else>Unavailable in Square</#if></td></tr>
                        </#list>
                    </tbody>
                </table>
            <#else><p>No Square locations were returned for this account.</p></#if>
        </div>
    </div>
    <div class="screenlet">
        <div class="screenlet-title-bar"><h2>Connection access</h2></div>
        <div class="screenlet-body"><p>Live account and location information is connected. The estimate and invoice workflow supports fee review and Square checkout for an explicitly bound issuing organization. Historical transaction imports are not enabled.</p></div>
    </div>
<#else>
    <div class="errorMessage" role="alert" data-square-status="disconnected">${connection.message!"Refresh the page to verify the Square connection."}</div>
    <p>The connection is verified live when this page opens. Refresh to try again.</p>
</#if>
