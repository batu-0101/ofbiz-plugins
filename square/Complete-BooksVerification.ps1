param([Parameter(Mandatory)][string]$Username, [Parameter(Mandatory)][string]$Password)
$ErrorActionPreference='Stop'
$base='https://localhost:8443'
function Login-App([string]$Path) {
    $login=Invoke-WebRequest ($base+$Path) -SkipCertificateCheck -SkipHttpErrorCheck -SessionVariable localSession
    $tag=[regex]::Match($login.Content,'<form\b[^>]*\bname="loginform"[^>]*>').Value
    $action=[Net.WebUtility]::HtmlDecode([regex]::Match($tag,'action="([^"]+)"').Groups[1].Value)
    if (-not $action) { throw 'The expected OFBiz login form was not returned.' }
    $page=Invoke-WebRequest $action -Method Post -Body @{USERNAME=$Username;PASSWORD=$Password;JavaScriptEnabled='Y'} -WebSession $localSession -SkipCertificateCheck -SkipHttpErrorCheck
    return @{Session=$localSession;Page=$page}
}
$evidence=Get-Content (Join-Path $PSScriptRoot '../../runtime/books-verification.json') -Raw | ConvertFrom-Json
$native=Login-App '/accounting/control/viewInvoice?invoiceId=CI1'
$checks=[System.Collections.Generic.List[object]]::new()
$checks.Add([pscustomobject]@{Name='Native invoice links to review and Pay Now';Passed=$native.Page.Content.Contains('Review invoice / Pay now')})
foreach ($id in (@('CI1',$evidence.InvoiceId,'BB_WEB_FEE') | Select-Object -Unique)) {
    $page=Invoke-WebRequest "$base/accounting/control/viewInvoice?invoiceId=$id" -WebSession $native.Session -SkipCertificateCheck -SkipHttpErrorCheck
    if ($page.Content -notmatch 'Synthetic (local workflow|fee-notice) verification') { throw "Refusing to cancel $id because the synthetic verification description was not found." }
    $cancelForm=[regex]::Matches($page.Content,'(?s)<form\b[^>]*>.*?</form>') | Where-Object { $_.Value.Contains('setInvoiceStatus') -and $_.Value.Contains('INVOICE_CANCELLED') } | Select-Object -First 1
    if ($cancelForm) {
        $action=[Net.WebUtility]::HtmlDecode([regex]::Match($cancelForm.Value,'action="([^"]+)"').Groups[1].Value)
        $fields=@{}
        foreach ($tag in [regex]::Matches($cancelForm.Value,'<input\b[^>]*>')) {
            $name=[regex]::Match($tag.Value,'name="([^"]+)"').Groups[1].Value
            $value=[regex]::Match($tag.Value,'value="([^"]*)"').Groups[1].Value
            if ($name) { $fields[$name]=[Net.WebUtility]::HtmlDecode($value) }
        }
        if ($fields['invoiceId'] -ne $id -or $fields['statusId'] -ne 'INVOICE_CANCELLED') { throw 'The cancellation form did not match the exact test record.' }
        $page=Invoke-WebRequest ([uri]::new([uri]$base,$action)) -Method Post -Body $fields -WebSession $native.Session -SkipCertificateCheck -SkipHttpErrorCheck
    }
    $passed=$page.StatusCode -eq 200 -and $page.Content -match 'Cancelled|Canceled' -and $page.Content -notmatch 'Error rendering|ERROR MESSAGE'
    $checks.Add([pscustomobject]@{Name="Synthetic invoice $id canceled through native status service";Passed=$passed})
}
$quote=Login-App '/ordermgr/control/ViewQuote?quoteId=BB_WEB_EST2'
$checks.Add([pscustomobject]@{Name='Native quote links to the converted document';Passed=($quote.Page.StatusCode -eq 200 -and $quote.Page.Content.Contains('Review estimate / invoice') -and $quote.Page.Content -notmatch 'Error rendering|ERROR MESSAGE')})
$result=[pscustomobject]@{CheckedAt=(Get-Date).ToString('o');Checks=$checks;JarSha256=(Get-FileHash (Join-Path $PSScriptRoot '../../build/libs/ofbiz.jar')).Hash}
$result | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $PSScriptRoot '../../runtime/books-native-verification.json') -Encoding utf8
$checks | Format-Table -AutoSize
if ($checks.Passed -contains $false) { throw 'A native document or cleanup check failed.' }
