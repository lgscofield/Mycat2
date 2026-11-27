param(
    [Parameter(Mandatory = $true)][string]$Jar,
    [Parameter(Mandatory = $true)][string]$EvidenceDir,
    [string]$JdkHome = 'D:\Program Files\BellSoft\LibericaJDK-21-Full',
    [string]$ServerHost = '127.0.0.1',
    [int]$Port = 3306
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$javac = Join-Path $JdkHome 'bin\javac.exe'
$java = Join-Path $JdkHome 'bin\java.exe'
if (-not (Test-Path $javac) -or -not (Test-Path $java)) { throw "JDK tools not found: $JdkHome" }
if (-not (Test-Path $Jar)) { throw "fat jar not found: $Jar" }

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Jar).Hash.ToLowerInvariant()
"$hash  $(Split-Path -Leaf $Jar)" | Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'fat-jar.sha256')
$classes = Join-Path $env:TEMP "mycat-idebt134-capacity-$([guid]::NewGuid())"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
try {
    & $javac -cp $Jar -d $classes (Join-Path $PSScriptRoot 'NativeCapacityProbe.java') 2>&1 |
        Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'compile.log')
    if ($LASTEXITCODE -ne 0) { throw "probe compilation failed" }

    & $java -cp "$Jar;$classes" NativeCapacityProbe $ServerHost $Port 2>&1 |
        Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'live-probe.log')
    if ($LASTEXITCODE -ne 0) { throw "native capacity live probe failed" }

    $log = Get-Content (Join-Path $EvidenceDir 'live-probe.log') -Raw
    if ($log -notmatch 'CAPACITY_LIVE PASS') { throw "missing CAPACITY_LIVE PASS marker" }
    'verdict	PASS	real_mysql_maxCon_1_second_borrow_rejected_third_borrow_recovered' |
        Set-Content -Encoding utf8 (Join-Path $EvidenceDir 'summary.tsv')
} finally {
    Remove-Item -Recurse -Force -LiteralPath $classes
}
