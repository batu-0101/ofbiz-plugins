param(
    [Parameter(Mandatory)][string]$Username,
    [Parameter(Mandatory)][string]$Password
)
$ErrorActionPreference = 'Stop'
$ofbizRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$url = 'https://localhost:8443/square/control/main'
$login = Invoke-WebRequest $url -SkipCertificateCheck -SkipHttpErrorCheck -SessionVariable squareSession
$form = [regex]::Match($login.Content, '<form\b[^>]*\bname="loginform"[^>]*>').Value
if (!$form) { throw 'The Square connection did not require the expected OFBiz sign-in.' }
$action = [Net.WebUtility]::HtmlDecode([regex]::Match($form, 'action="([^"]+)"').Groups[1].Value)
$page = Invoke-WebRequest $action -Method Post -Body @{USERNAME=$Username;PASSWORD=$Password;JavaScriptEnabled='Y'} -WebSession $squareSession -SkipCertificateCheck -SkipHttpErrorCheck
$refresh = Invoke-WebRequest $url -WebSession $squareSession -SkipCertificateCheck -SkipHttpErrorCheck
. (Join-Path $ofbizRoot 'Windows-CredentialVault.ps1')
$token = [OFBizCredentialVault]::ReadSquareAccessToken()
try {
    $checks = [ordered]@{
        AnonymousRequiresLogin=($login.StatusCode -eq 401)
        AnonymousHasNoMerchant=(!$login.Content.Contains('ML5VGNZPYK1E8'))
        SignedInStatus=($page.StatusCode -eq 200)
        Connected=$page.Content.Contains('data-square-status="connected"')
        MerchantMatches=$page.Content.Contains('ML5VGNZPYK1E8')
        LocationMatches=$page.Content.Contains('LD5XGDRB6S7GX')
        RefreshConnected=$refresh.Content.Contains('data-square-status="connected"')
        BeyondBooksTheme=$page.Content.Contains('/beyond-books/beyond-books.css')
        ResponseNotCached=(($page.Headers['Cache-Control'] -join ',').Contains('no-store'))
        CredentialNotInResponse=(![string]::IsNullOrEmpty($token) -and !$page.Content.Contains($token) -and !$refresh.Content.Contains($token))
        NoRenderError=($page.Content -notmatch 'ERROR MESSAGE|Error rendering|java\.lang\.(Security|Runtime)Exception')
    }
} finally { $token = $null }
$tests = @(Get-ChildItem -LiteralPath (Join-Path $ofbizRoot 'build/test-results/test') -Filter 'TEST-org.apache.ofbiz.square.*.xml' | ForEach-Object {
    [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw
    [pscustomobject]@{Suite=$xml.testsuite.name;Tests=[int]$xml.testsuite.tests;Failures=[int]$xml.testsuite.failures;Errors=[int]$xml.testsuite.errors}
})
$report = [ordered]@{
    CheckedAt=(Get-Date).ToString('o')
    Merchant='Home & Beyond Services'
    Environment='production'
    ConnectionScope='Account and location reads; no payment execution or imports'
    Checks=$checks
    Tests=$tests
    JarSha256=(Get-FileHash -LiteralPath (Join-Path $ofbizRoot 'build/libs/ofbiz.jar')).Hash
}
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $ofbizRoot 'runtime/square-verification.json') -Encoding utf8
$checks | ConvertTo-Json
$tests | Format-Table -AutoSize
if (@($checks.Values | Where-Object { !$_ }).Count -gt 0) { throw 'One or more live Square connection checks failed.' }
