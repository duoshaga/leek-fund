param(
    [ValidateSet('live','restart','monthly','close','fuzzy','index-search','appearance','watch-order','order-restart','update-source','refresh-hours','hours-restart','hours-legacy','hours-discovery','hours-cache','fund-legacy','fund-quotes')][string]$Phase = 'live',
    [switch]$Install,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '1.0.1',
    [ValidatePattern('^rider-263-[A-Za-z0-9-]+$')][string]$ProfileName = ('rider-263-release-' + $Version.Replace('.', ''))
)
$ErrorActionPreference = 'Stop'
$modulePath = (Get-Item -LiteralPath (Join-Path $PSScriptRoot '../..')).FullName
$repo = Split-Path $modulePath
$rider = (Get-Command rider64.exe).Source
$ide = Split-Path (Split-Path $rider)
$product = Get-Content -Raw -LiteralPath "$ide/product-info.json" | ConvertFrom-Json
if ($product.productCode -ne 'RD' -or $product.buildNumber -ne '263.7307.355') { throw 'Rider build mismatch' }
$profileRoot = Join-Path $env:LOCALAPPDATA "LeekBox/$ProfileName"
$evidence = Join-Path $modulePath 'build/acceptance'
$config = Join-Path $profileRoot 'config'
$pluginRoot = Join-Path $profileRoot 'plugins'
# Rider's installPlugins skips an installed ID. The fund upgrade reuses the real
# saved watchlist in config while each version loads its own distribution.
if ($Phase -in 'fund-legacy','fund-quotes') { $pluginRoot = Join-Path $pluginRoot $Version }
$archive = Get-Item -LiteralPath "$modulePath/dist/leekbox-rider-$Version.zip"
$marker = Join-Path $profileRoot 'leekbox-acceptance-profile'
if ((Test-Path $profileRoot) -and !(Test-Path $marker)) { throw "Directory already exists outside this acceptance run: $profileRoot" }
$lock = Join-Path $config '.lock'
if (Test-Path $lock) {
    $owner = (Get-Content -LiteralPath $lock -Raw).Trim()
    if ($owner -match '^\d+$' -and (Get-Process -Id ([int]$owner) -ErrorAction SilentlyContinue)) {
        throw "The isolated profile is still open in process $owner"
    }
}
New-Item -ItemType Directory -Force -Path "$config/options","$config/extensions/com.intellij/startup",$pluginRoot,"$profileRoot/system","$profileRoot/log",$evidence | Out-Null
$repo | Set-Content -LiteralPath $marker -Encoding utf8NoBOM
$properties = @(
    "idea.config.path=$config", "idea.system.path=$profileRoot/system",
    "idea.log.path=$profileRoot/log", "idea.plugins.path=$pluginRoot",
    "leek.qa.repo=$repo", "leek.qa.profile=$profileRoot", "leek.qa.version=$Version"
) -replace '\\','/'
$properties | Set-Content -LiteralPath "$profileRoot/idea.properties" -Encoding utf8NoBOM
'Git4Idea','org.jetbrains.plugins.github','org.jetbrains.plugins.gitlab','rider.intellij.plugin.appender' |
    Set-Content -LiteralPath "$config/disabled_plugins.txt" -Encoding utf8NoBOM
# Only the original repository and an existing subdirectory are opened.
$xmlRepo = [Security.SecurityElement]::Escape($repo)
$second = [Security.SecurityElement]::Escape((Join-Path $repo 'template-packages/leek-center'))
"<application><component name='TrustedSolutionStore'><option name='trustedLocations'><set><option value='$xmlRepo'/><option value='$second'/></set></option></component></application>" |
    Set-Content -LiteralPath "$config/options/trustedSolutions.xml" -Encoding utf8NoBOM
$repoUrl = $xmlRepo.Replace('\','/')
$secondUrl = $second.Replace('\','/')
"<application><component name='Trusted.Paths'><option name='TRUSTED_PROJECT_PATHS'><map><entry key='$repoUrl' value='true'/><entry key='$secondUrl' value='true'/></map></option></component></application>" |
    Set-Content -LiteralPath "$config/options/trusted-paths.xml" -Encoding utf8NoBOM
Copy-Item -LiteralPath "$PSScriptRoot/VerifyRider.groovy" -Destination "$config/extensions/com.intellij/startup/verify.groovy"
$Phase | Set-Content -LiteralPath "$profileRoot/phase.txt" -Encoding utf8NoBOM
$previousProperties = $env:RIDER_PROPERTIES
try {
    $env:RIDER_PROPERTIES = Join-Path $profileRoot 'idea.properties'
    if ($Install) {
        $catalog = Join-Path $evidence 'disk-repository.xml'
        $zipUri = ([uri]$archive.FullName).AbsoluteUri
        "<plugins><plugin id='cn.leekbox.quotes' url='$zipUri' version='$Version'><name>LeekBox</name><idea-version since-build='263.7307.355'/></plugin></plugins>" |
            Set-Content -LiteralPath $catalog -Encoding utf8NoBOM
        $arguments = @('installPlugins','cn.leekbox.quotes',([uri]$catalog).AbsoluteUri,'--give-consent-to-use-third-party-plugins')
        $installRun = Start-Process -FilePath $rider -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput "$evidence/install.out" -RedirectStandardError "$evidence/install.err"
        if ($installRun.ExitCode -ne 0) { throw "Local ZIP installation failed. See $evidence/install.err" }
    }
    $zip = [IO.Compression.ZipFile]::OpenRead($archive.FullName)
    try {
        $jars = @($zip.Entries | Where-Object { $_.FullName -like '*/lib/*.jar' })
        if ($jars.Count -ne 1) { throw 'Unexpected plugin archive layout' }
        $stream = $jars[0].Open()
        try { $expectedJar = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) }
        finally { $stream.Dispose() }
        $installedJar = Join-Path $pluginRoot $jars[0].FullName
        if ((Get-FileHash -LiteralPath $installedJar -Algorithm SHA256).Hash -ne $expectedJar) {
            throw 'The installed JAR differs from the requested ZIP'
        }
    } finally { $zip.Dispose() }
    $run = Start-Process -FilePath $rider -ArgumentList ('"' + $repo + '"') -WindowStyle Hidden -PassThru
    [pscustomobject]@{ Phase=$Phase; ProcessId=$run.Id; Profile=$profileRoot; Evidence="$evidence/$Phase.json" }
} finally {
    $env:RIDER_PROPERTIES = $previousProperties
}
