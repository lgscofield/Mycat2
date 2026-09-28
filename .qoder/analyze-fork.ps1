$basePath = 'D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\'
$forkFiles = Get-ChildItem -Path "$basePath\org\apache\calcite" -Recurse -Filter '*.java' | ForEach-Object {
    $rel = $_.FullName.Substring($basePath.Length)
    ($rel -replace '\\', '/' -replace '\.java$', '') -replace '/', '.'
}

$calciteClasses = Get-Content "$env:TEMP\calcite-classes.txt"

Write-Host "=== hbt fork files analysis ==="
Write-Host "Total fork files: $($forkFiles.Count)"

$overrides = @()
$newClasses = @()
foreach ($f in $forkFiles) {
    if ($calciteClasses -contains $f) {
        $overrides += $f
    } else {
        $newClasses += $f
    }
}

Write-Host "Overrides existing Calcite classes: $($overrides.Count)"
Write-Host "New classes (not in Calcite): $($newClasses.Count)"
Write-Host ""
Write-Host "=== NEW CLASSES (Mycat additions in org.apache.calcite namespace) ==="
$newClasses | Sort-Object | ForEach-Object { Write-Host "  $_" }
Write-Host ""
Write-Host "=== OVERRIDE CLASSES (replace Calcite originals) ==="
$overrides | Sort-Object | ForEach-Object { Write-Host "  $_" }
