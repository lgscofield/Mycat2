$basePath = 'D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\'
$forkFiles = Get-ChildItem -Path "$basePath\org\apache\calcite" -Recurse -Filter '*.java'
$calciteClasses = Get-Content "$env:TEMP\calcite-classes.txt"

Write-Host "========================================="
Write-Host "  FORK FILE CLASSIFICATION"
Write-Host "========================================="
Write-Host ""

foreach ($file in ($forkFiles | Sort-Object FullName)) {
    $rel = $file.FullName.Substring($basePath.Length)
    $fqn = ($rel -replace '\\', '/' -replace '\.java$', '') -replace '/', '.'
    $content = Get-Content $file.FullName -Raw
    $hasMycatImport = $content -match 'import io\.mycat\.'
    $isOverride = $calciteClasses -contains $fqn

    $type = ""
    if ($isOverride -and -not $hasMycatImport) { $type = "PURE_OVERRIDE" }
    elseif ($isOverride -and $hasMycatImport) { $type = "BRIDGE_OVERRIDE" }
    elseif (-not $isOverride -and -not $hasMycatImport) { $type = "NEW_PURE" }
    else { $type = "NEW_BRIDGE" }

    $shortPath = $file.FullName.Substring($basePath.Length + 'org\apache\calcite\'.Length)
    $line = "{0,-55} {1,-18}" -f $shortPath, $type

    if ($type -eq "PURE_OVERRIDE") {
        Write-Host $line -ForegroundColor Green
    } elseif ($type -eq "BRIDGE_OVERRIDE") {
        Write-Host $line -ForegroundColor Yellow
    } elseif ($type -eq "NEW_PURE") {
        Write-Host $line -ForegroundColor Cyan
    } else {
        Write-Host $line -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "========================================="
Write-Host "  SUMMARY"
Write-Host "========================================="

$pureOverride = 0; $bridgeOverride = 0; $newPure = 0; $newBridge = 0
foreach ($file in $forkFiles) {
    $rel = $file.FullName.Substring($basePath.Length)
    $fqn = ($rel -replace '\\', '/' -replace '\.java$', '') -replace '/', '.'
    $content = Get-Content $file.FullName -Raw
    $hasMycatImport = $content -match 'import io\.mycat\.'
    $isOverride = $calciteClasses -contains $fqn
    if ($isOverride -and -not $hasMycatImport) { $pureOverride++ }
    elseif ($isOverride -and $hasMycatImport) { $bridgeOverride++ }
    elseif (-not $isOverride -and -not $hasMycatImport) { $newPure++ }
    else { $newBridge++ }
}

Write-Host "PURE_OVERRIDE  (same class as Calcite, no io.mycat):  $pureOverride  --> calcite-core fork"
Write-Host "BRIDGE_OVERRIDE (same class as Calcite, has io.mycat): $bridgeOverride  --> stays in hbt"
Write-Host "NEW_PURE       (Mycat addition, no io.mycat):          $newPure  --> calcite-core fork"
Write-Host "NEW_BRIDGE     (Mycat addition, has io.mycat):         $newBridge  --> stays in hbt"
Write-Host "TOTAL: $($pureOverride + $bridgeOverride + $newPure + $newBridge)"
