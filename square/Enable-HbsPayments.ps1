param(
    [Parameter(Mandatory)][string]$Username,
    [Parameter(Mandatory)][string]$Password
)
$ErrorActionPreference = 'Stop'
$ofbizRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
. (Join-Path $ofbizRoot 'Windows-CredentialVault.ps1')
$token = [OFBizCredentialVault]::ReadSquareAccessToken()
try {
    $headers = @{Authorization="Bearer $token"; 'Square-Version'='2026-09-16'}
    $status = Invoke-RestMethod 'https://connect.squareup.com/oauth2/token/status' -Method Post -Headers $headers -ContentType 'application/json' -Body '{}'
    $location = (Invoke-RestMethod 'https://connect.squareup.com/v2/locations/LD5XGDRB6S7GX' -Headers $headers).location
    $requiredScopes = @('ORDERS_READ','ORDERS_WRITE','PAYMENTS_READ','PAYMENTS_WRITE','MERCHANT_PROFILE_READ')
    $scopeCheck = @($requiredScopes | Where-Object { $_ -notin $status.scopes }).Count -eq 0
    if ($status.merchant_id -ne 'ML5VGNZPYK1E8' -or !$scopeCheck -or $location.status -ne 'ACTIVE' -or
        $location.merchant_id -ne $status.merchant_id -or $location.currency -ne 'USD' -or 'CREDIT_CARD_PROCESSING' -notin $location.capabilities) {
        throw 'The production account, location or required payment permissions could not be verified.'
    }
} catch { throw 'Square payment readiness verification failed. No settings were changed; provider response withheld.' }
finally { $token = $null; $headers = $null; $status = $null; $location = $null }

$url = 'https://localhost:8443/square/control/booksSettings?partyId=HBS_SQUARE'
$login = Invoke-WebRequest $url -SkipCertificateCheck -SkipHttpErrorCheck -SessionVariable session
$form = [regex]::Match($login.Content, '<form\b[^>]*\bname="loginform"[^>]*>').Value
if (!$form) { throw 'Expected OFBiz sign-in was not available.' }
$action = [Net.WebUtility]::HtmlDecode([regex]::Match($form, 'action="([^"]+)"').Groups[1].Value)
$null = Invoke-WebRequest $action -Method Post -Body @{USERNAME=$Username;PASSWORD=$Password;JavaScriptEnabled='Y'} -WebSession $session -SkipCertificateCheck -SkipHttpErrorCheck
$page = Invoke-WebRequest $url -WebSession $session -SkipCertificateCheck -SkipHttpErrorCheck
if ($page.StatusCode -ne 200 -or $page.Content -notmatch '<option value="HBS_SQUARE" selected>Home &amp; Beyond Services') {
    throw 'The distinct Home & Beyond Services issuing organization has not been installed.'
}
$changed = $false
if (!$page.Content.Contains('data-payment-enabled="true"')) {
    $csrf = [regex]::Match($page.Content, 'name="booksCsrf" value="([^"]+)"').Groups[1].Value
    if (!$csrf) { throw 'The authenticated settings form was not available.' }
    $post = Invoke-WebRequest 'https://localhost:8443/square/control/booksSettings' -Method Post -WebSession $session -SkipCertificateCheck -SkipHttpErrorCheck -Body @{
        booksCsrf=$csrf;partyId='HBS_SQUARE';feeBps='0';feeFixedCents='0';confirmMerchant='true'
    }
    if ($post.StatusCode -ne 200 -or !$post.Content.Contains('Payment settings saved for HBS_SQUARE.')) {
        throw 'OFBiz did not confirm payment settings were saved.'
    }
    $changed = $true
}
$current = Invoke-WebRequest $url -WebSession $session -SkipCertificateCheck -SkipHttpErrorCheck
$sample = Invoke-WebRequest 'https://localhost:8443/square/control/booksSettings?partyId=Company' -WebSession $session -SkipCertificateCheck -SkipHttpErrorCheck
$home = Invoke-WebRequest 'https://localhost:8443/square/control/booksSettings' -WebSession $session -SkipCertificateCheck -SkipHttpErrorCheck
$checks = [ordered]@{
    ProductionMerchantAndActiveLocationVerified=$true
    CardProcessingCapabilityVerified=$true
    RequiredPaymentPermissionsVerified=$scopeCheck
    AnonymousRequiresLogin=($login.StatusCode -eq 401)
    PaymentBindingPersisted=$current.Content.Contains('data-payment-enabled="true"')
    CorrectIssuerSelected=($current.Content -match '<option value="HBS_SQUARE" selected>')
    NoCustomerFee=($current.Content -match 'name="feeBps"[^>]*value="0"' -and $current.Content -match 'name="feeFixedCents"[^>]*value="0"' -and $current.Content -notmatch 'name="feeEnabled"[^>]*checked')
    SampleIssuerStillDisabled=$sample.Content.Contains('data-payment-enabled="false"')
    DefaultSettingsShowBoundIssuer=($home.Content.Contains('data-payment-enabled="true"') -and $home.Content -match '<option value="HBS_SQUARE" selected>')
    NativePaymentRefreshExplained=$current.Content.Contains('Refresh Square payment status')
    SettingsNotCached=(($current.Headers['Cache-Control'] -join ',').Contains('no-store'))
    NoRenderError=($current.Content -notmatch 'ERROR MESSAGE|Error rendering|java\.lang\.(Security|Runtime)Exception')
}
$report = [ordered]@{
    CheckedAt=(Get-Date).ToString('o'); Environment='production'; Merchant='Home & Beyond Services'
    IssuingPartyId='HBS_SQUARE'; LocationId='LD5XGDRB6S7GX'; SettingsChanged=$changed
    Checks=$checks; LiveChargePerformed=$false; BankFeedConnected=$false
    JarSha256=(Get-FileHash -LiteralPath (Join-Path $ofbizRoot 'build/libs/ofbiz.jar')).Hash
}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $ofbizRoot 'runtime/payment-enablement.json') -Encoding utf8
$checks | ConvertTo-Json
if (@($checks.Values | Where-Object { !$_ }).Count) { throw 'Payment enablement verification failed.' }
