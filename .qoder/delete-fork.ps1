$base = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite"

# Bridge files to KEEP (have io.mycat imports)
$bridgeFiles = @(
    "DataContext.java",
    "MycatContext.java",
    "adapter\enumerable\RexToLixTranslator.java",
    "mycat\MycatBuiltInMethodImpl.java",
    "rel\externalize\RelJson.java",
    "rel\externalize\RelJsonWriter.java",
    "rel\metadata\RelMdCollation.java",
    "rel\metadata\RelMdMaxRowCount.java",
    "rel\metadata\RelMdRowCount.java",
    "rel\metadata\RelMdUniqueKeys.java",
    "rel\metadata\RelMdUtil.java",
    "rel\rules\MycatHepJoinClustering.java",
    "rex\RexLiteral.java",
    "runtime\NewMycatDataContext.java",
    "sql\validate\SqlValidatorImpl.java",
    "util\RxBuiltInMethodImpl.java"
)

$allFiles = Get-ChildItem -Path $base -Recurse -Filter "*.java"
$deleted = 0
$kept = 0

foreach ($f in $allFiles) {
    $rel = $f.FullName.Substring($base.Length + 1)
    if ($bridgeFiles -contains $rel) {
        $kept++
    } else {
        Remove-Item $f.FullName -Force
        $deleted++
        Write-Host "DELETED: $rel"
    }
}

Write-Host ""
Write-Host "Deleted: $deleted files"
Write-Host "Kept: $kept bridge files"

# Verify remaining files
$remaining = Get-ChildItem -Path $base -Recurse -Filter "*.java"
Write-Host "Remaining files: $($remaining.Count)"

