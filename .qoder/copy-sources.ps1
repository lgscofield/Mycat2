$src = Join-Path $env:TEMP "calcite-1.42-sources"
$dest = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java"
$resDest = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\resources\META-INF"

# Copy Java files
$count = 0
Get-ChildItem -Path $src -Recurse -Filter "*.java" | ForEach-Object {
    $rel = $_.FullName.Substring($src.Length + 1)
    $destFile = Join-Path $dest $rel
    $destDir = Split-Path $destFile -Parent
    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }
    Copy-Item $_.FullName $destFile -Force
    $count++
}
Write-Host "Copied $count Java files"

# Verify
$actual = (Get-ChildItem -Path $dest -Recurse -Filter "*.java" | Measure-Object).Count
Write-Host "Verified: $actual files in destination"

# Copy META-INF resources
New-Item -ItemType Directory -Path $resDest -Force | Out-Null
$tempExtract = Join-Path $env:TEMP "calcite-1.42-extract"
if (Test-Path (Join-Path $tempExtract "META-INF")) {
    Copy-Item (Join-Path $tempExtract "META-INF\LICENSE") $resDest -Force -ErrorAction SilentlyContinue
    Copy-Item (Join-Path $tempExtract "META-INF\NOTICE") $resDest -Force -ErrorAction SilentlyContinue
    Write-Host "Copied META-INF resources"
} else {
    # Try from source dir
    if (Test-Path (Join-Path $src "META-INF")) {
        Copy-Item (Join-Path $src "META-INF\LICENSE") $resDest -Force -ErrorAction SilentlyContinue
        Copy-Item (Join-Path $src "META-INF\NOTICE") $resDest -Force -ErrorAction SilentlyContinue
        Write-Host "Copied META-INF from sources dir"
    } else {
        Write-Host "META-INF not found in either location"
    }
}

# Check key files
$checks = @(
    "org\apache\calcite\package-info.java",
    "org\apache\calcite\sql\SqlLiteral.java",
    "org\apache\calcite\adapter\enumerable\RexImpTable.java",
    "org\apache\calcite\config\CalciteSystemProperty.java"
)
foreach ($f in $checks) {
    $full = Join-Path $dest $f
    if (Test-Path $full) { Write-Host "OK: $f" } else { Write-Host "MISSING: $f" }
}
