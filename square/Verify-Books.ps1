param([Parameter(Mandatory)][string]$Username, [Parameter(Mandatory)][string]$Password, [string]$QuoteId='BB_WEB_EST2')
$ErrorActionPreference = 'Stop'
$base = 'https://localhost:8443'
$checks = [System.Collections.Generic.List[object]]::new()
function Check([string]$Name, [bool]$Passed) { $checks.Add([pscustomobject]@{ Name=$Name; Passed=$Passed }); if (-not $Passed) { Write-Warning $Name } }
function Get-Form([string]$Html, [string]$Action) {
    $forms = [regex]::Matches($Html, '(?s)<form\b[^>]*>.*?</form>')
    foreach ($candidate in $forms) {
        $actionMatch = [regex]::Match($candidate.Value, 'action="([^"]+)"')
        if ($actionMatch.Success -and $actionMatch.Groups[1].Value.Contains($Action)) {
            $fields = @{}
            foreach ($inputTag in [regex]::Matches($candidate.Value, '<input\b[^>]*>')) {
                $name = [regex]::Match($inputTag.Value, 'name="([^"]+)"').Groups[1].Value
                $value = [regex]::Match($inputTag.Value, 'value="([^"]*)"').Groups[1].Value
                if ($name) { $fields[$name] = [Net.WebUtility]::HtmlDecode($value) }
            }
            return @{ Uri=[uri]::new([uri]$base,[Net.WebUtility]::HtmlDecode($actionMatch.Groups[1].Value)); Fields=$fields }
        }
    }
    throw "The page did not contain the expected $Action form."
}
function Get-Page([string]$Path) { Invoke-WebRequest ($base+$Path) -WebSession $script:webSession -SkipCertificateCheck -SkipHttpErrorCheck }
function Post-Form($Form) { Invoke-WebRequest $Form.Uri -Method Post -Body $Form.Fields -WebSession $script:webSession -SkipCertificateCheck -SkipHttpErrorCheck }
function No-RenderError($Page) { return $Page.Content -notmatch 'Error rendering|ERROR MESSAGE|An error occurred|java\.lang\.(Security|Runtime)Exception' }
$login = Invoke-WebRequest ($base+'/square/control/books') -SkipCertificateCheck -SkipHttpErrorCheck -SessionVariable webSession
Check 'Anonymous documents require login' ($login.StatusCode -eq 401)
$loginForm = Get-Form $login.Content 'login'
$loginForm.Fields['USERNAME']=$Username
$loginForm.Fields['PASSWORD']=$Password
$loginForm.Fields['JavaScriptEnabled']='Y'
$list = Post-Form $loginForm
Check 'Signed-in document list renders' ($list.StatusCode -eq 200 -and $list.Content.Contains('Estimates &amp; invoices') -and (No-RenderError $list))
$estimate = Get-Page "/square/control/document?quoteId=$QuoteId"
Check 'Native estimate renders with exact total and terms' ($estimate.StatusCode -eq 200 -and $estimate.Content.Contains('USD 100.00') -and $estimate.Content.Contains('Net 30 verification') -and (No-RenderError $estimate))
$approval = Get-Form $estimate.Content 'approveEstimate'
$approved = Post-Form $approval
Check 'Approval changes the visible document to invoice' ($approved.StatusCode -eq 200 -and $approved.Content.Contains('Your estimate is now invoice') -and $approved.Content.Contains("From estimate $QuoteId") -and (No-RenderError $approved))
$invoiceId = [regex]::Match($approved.Content,'id="document-number">([^<]+)').Groups[1].Value
if (-not $invoiceId) { throw 'Approval did not return a real invoice number.' }
$replayed = Post-Form $approval
$replayedId = [regex]::Match($replayed.Content,'id="document-number">([^<]+)').Groups[1].Value
Check 'Duplicate HTTP approval returns the same invoice' ($invoiceId -eq $replayedId)
$revisit = Get-Page "/square/control/document?quoteId=$QuoteId"
Check 'Original estimate URL now shows its linked invoice' ($revisit.Content.Contains('id="document-kind">Invoice') -and $revisit.Content.Contains($invoiceId))
$feePage = Get-Page '/square/control/document?invoiceId=BB_WEB_FEE'
Check 'Pay Now includes the fee review dialog' ($feePage.StatusCode -eq 200 -and $feePage.Content.Contains('data-books-pay') -and $feePage.Content.Contains('Card processing fee') -and (No-RenderError $feePage))
Check 'Notice shows principal fee total and alternative' ($feePage.Content.Contains('USD 100.00') -and $feePage.Content.Contains('USD 3.30') -and $feePage.Content.Contains('USD 103.30') -and $feePage.Content.Contains('paying by check'))
$accept = Get-Form $feePage.Content 'acceptCardFee'
$stale = @{Uri=$accept.Uri; Fields=$accept.Fields.Clone()}
$stale.Fields['totalCents']='10329'
$badTotal = Post-Form $stale
Check 'Changed fee total is refused with review instruction' ($badTotal.StatusCode -eq 409 -and $badTotal.Content.Contains('card amount changed'))
$missing = @{Uri=$accept.Uri; Fields=$accept.Fields.Clone()}
$missing.Fields['acknowledged']='false'
$noConsent = Post-Form $missing
Check 'Missing explicit fee acceptance is refused' ($noConsent.StatusCode -eq 409 -and $noConsent.Content.Contains('Review and accept'))
$csrf = @{Uri=$accept.Uri; Fields=$accept.Fields.Clone()}
$csrf.Fields['booksCsrf']='invalid'
$noCsrf = Post-Form $csrf
Check 'Forged POST is forbidden' ($noCsrf.StatusCode -eq 403)
$accepted = Post-Form $accept
Check 'Synthetic issuer cannot charge the production merchant' ($accepted.Content.Contains('Square checkout is not enabled for this invoice') -and (No-RenderError $accepted))
Check 'Accepting the notice does not change invoice balance' ($accepted.Content -match 'Balance due\s*<strong>USD 100\.00' -and $accepted.Content.Contains('ACCEPTED'))
$settings = Get-Page '/square/control/booksSettings'
Check 'Payment settings render for administrator' ($settings.StatusCode -eq 200 -and $settings.Content.Contains('Save Square payment settings') -and (No-RenderError $settings))
foreach ($asset in @('/square/images/books.js','/square/images/books.css')) {
    $assetResponse = Get-Page $asset
    Check "Asset $asset is served" ($assetResponse.StatusCode -eq 200)
}
Check 'Sensitive document response disables caching' (($feePage.Headers['Cache-Control'] -join ',') -match 'no-store')
$evidence = [pscustomobject]@{CheckedAt=(Get-Date).ToString('o'); InvoiceId=$invoiceId; Checks=$checks; LiveChargeMade=$false; JarSha256=(Get-FileHash (Join-Path $PSScriptRoot '../../build/libs/ofbiz.jar')).Hash}
$evidence | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $PSScriptRoot '../../runtime/books-verification.json') -Encoding utf8
$checks | Format-Table -AutoSize
if ($checks.Passed -contains $false) { throw 'One or more workflow HTTP checks failed.' }
