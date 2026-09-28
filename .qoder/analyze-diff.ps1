$extractDir = "$env:TEMP\calcite-1.42-sources"
$basePath = 'D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\'

# Sample files to analyze
$samples = @(
    'org\apache\calcite\config\CalciteSystemProperty.java',
    'org\apache\calcite\plan\RelOptCostImpl.java',
    'org\apache\calcite\plan\volcano\VolcanoCost.java',
    'org\apache\calcite\rel\RelInput.java',
    'org\apache\calcite\rex\RexExecutable.java',
    'org\apache\calcite\runtime\Bindable.java',
    'org\apache\calcite\sql\SqlOperatorTable.java',
    'org\apache\calcite\sql\SqlUtil.java',
    'org\apache\calcite\sql\type\SqlTypeName.java',
    'org\apache\calcite\tools\Frameworks.java'
)

foreach ($rel in $samples) {
    $mycatPath = Join-Path $basePath $rel
    $origPath = Join-Path $extractDir ($rel -replace '\\', '/')

    Write-Host "============================================================"
    Write-Host "FILE: $rel"
    Write-Host "============================================================"

    if ((Test-Path $mycatPath) -and (Test-Path $origPath)) {
        $diffOutput = & 'D:\Program Files\Git\usr\bin\diff.exe' --unified=2 --label "Calcite 1.42.0" --label "Mycat fork" $origPath $mycatPath 2>&1
        $diffLines = $diffOutput | Out-String
        $additions = ($diffOutput | Where-Object { $_ -match '^\+[^+]' }).Count
        $deletions = ($diffOutput | Where-Object { $_ -match '^\-[^-]' }).Count
        Write-Host "  Additions: +$additions  Deletions: -$deletions"
        Write-Host ""
        # Show first 40 lines of diff
        $diffOutput | Select-Object -First 50 | ForEach-Object { Write-Host $_ }
    }
    Write-Host ""
}
