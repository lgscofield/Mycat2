$basePath = 'D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\'
$calciteClasses = Get-Content "$env:TEMP\calcite-classes.txt"

# Get PURE_OVERRIDE files (same as Calcite, no io.mycat)
$forkFiles = Get-ChildItem -Path "$basePath\org\apache\calcite" -Recurse -Filter '*.java'
$pureOverrides = @()
foreach ($file in $forkFiles) {
    $rel = $file.FullName.Substring($basePath.Length)
    $fqn = ($rel -replace '\\', '/' -replace '\.java$', '') -replace '/', '.'
    $content = Get-Content $file.FullName -Raw
    $hasMycatImport = $content -match 'import io\.mycat\.'
    $isOverride = $calciteClasses -contains $fqn
    if ($isOverride -and -not $hasMycatImport) {
        $pureOverrides += $file
    }
}

# For each pure override, compute a rough diff size against the original
$jdk = 'D:\Program Files\BellSoft\LibericaJDK-21-Full'
$jar = 'D:\Nexus\repository\org\apache\calcite\calcite-core\1.42.0\calcite-core-1.42.0-sources.jar'
$extractDir = "$env:TEMP\calcite-1.42-sources"

# Extract sources once if not done
if (-not (Test-Path $extractDir)) {
    New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
    Push-Location $extractDir
    & "$jdk\bin\jar.exe" xf $jar
    Pop-Location
}

Write-Host "=== PURE_OVERRIDE: Modification Analysis ==="
Write-Host ""
Write-Host "{0,-60} {1,-8} {2,-8} {3,-10}" -f "File", "MycatLines", "OrigLines", "DiffRatio"
Write-Host ("-" * 90)

$totalModified = 0
$totalClean = 0

foreach ($file in ($pureOverrides | Sort-Object FullName)) {
    $rel = $file.FullName.Substring($basePath.Length)
    $origPath = Join-Path $extractDir ($rel -replace '\\', '/')
    
    $mycatLines = (Get-Content $file.FullName).Count
    
    if (Test-Path $origPath) {
        $origLines = (Get-Content $origPath).Count
        if ($origLines -gt 0) {
            $ratio = [math]::Round($mycatLines / $origLines, 2)
        } else {
            $ratio = "N/A"
        }
        
        # Quick similarity check
        $mycatContent = Get-Content $file.FullName -Raw
        $origContent = Get-Content $origPath -Raw
        
        if ($mycatContent -eq $origContent) {
            $status = "IDENTICAL"
            $totalClean++
        } else {
            $status = "MODIFIED"
            $totalModified++
        }
        
        Write-Host "{0,-60} {1,-8} {2,-8} {3,-10}" -f $rel, $mycatLines, $origLines, "$ratio $status"
    } else {
        Write-Host "{0,-60} {1,-8} {2,-8} {3,-10}" -f $rel, $mycatLines, "N/A", "NO_ORIG"
    }
}

Write-Host ""
Write-Host "=== SUMMARY ==="
Write-Host "Identical to Calcite 1.42.0 (can delete from fork): $totalClean"
Write-Host "Modified from Calcite 1.42.0 (need merge analysis): $totalModified"
