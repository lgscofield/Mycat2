$extractDir = "$env:TEMP\calcite-1.42-sources"
$basePath = 'D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\'
$calciteClasses = Get-Content "$env:TEMP\calcite-classes.txt"

# Get all PURE_OVERRIDE files
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

# For each pure override, check if Mycat version has Mycat-specific additions
# by looking for patterns that indicate Mycat customization vs version drift
Write-Host "=== MYCAT CUSTOMIZATION DETECTION ==="
Write-Host ""

foreach ($file in ($pureOverrides | Sort-Object FullName)) {
    $rel = $file.FullName.Substring($basePath.Length)
    $content = Get-Content $file.FullName -Raw
    
    # Check for Mycat-specific patterns
    $mycatPatterns = @(
        @{ Name = "Mycat references"; Pattern = 'mycat|Mycat|MYCAT' },
        @{ Name = "MySQL-specific"; Pattern = 'MySQL|mysql|MYSQL' },
        @{ Name = "Custom methods"; Pattern = '//\s*(add|custom|mycat|modify|change|hack|workaround)' },
        @{ Name = "Apache license stripped"; Pattern = 'Licensed to the Apache' }
    )
    
    $findings = @()
    foreach ($p in $mycatPatterns) {
        if ($content -match $p.Pattern) {
            $findings += $p.Name
        }
    }
    
    $hasLicense = $content -match 'Licensed to the Apache'
    $shortName = $file.Name
    
    if ($findings.Count -eq 0 -or ($findings.Count -eq 1 -and $findings[0] -eq "Apache license stripped")) {
        $verdict = "LIKELY_VERSION_DRIFT"
    } else {
        $verdict = "HAS_MYCAT_CUSTOM"
    }
    
    $findingsStr = ($findings -join ", ")
    if ($findingsStr.Length -gt 60) { $findingsStr = $findingsStr.Substring(0, 57) + "..." }
    
    Write-Host "{0,-45} {1,-25} [{2}]" -f $rel, $verdict, $findingsStr
}
