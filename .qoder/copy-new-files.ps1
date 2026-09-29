$hbtBase = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite"
$forkBase = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java\org\apache\calcite"

$files = @(
    "mycat\MycatBuiltInMethod.java",
    "mycat\MycatSqlDefinedFunction.java",
    "sql\SqlForUpdate.java",
    "sql\MysqlExactNumericLiteral.java",
    "util\RxBuiltInMethod.java"
)

foreach ($f in $files) {
    $src = Join-Path $hbtBase $f
    $dest = Join-Path $forkBase $f
    $destDir = Split-Path $dest -Parent
    if (-not (Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    }
    Copy-Item $src $dest -Force
    
    # Check for io.mycat imports
    $content = Get-Content $dest -Raw
    if ($content -match "import\s+io\.mycat") {
        Write-Host "WARNING: $f contains io.mycat import!"
    } else {
        Write-Host "OK: $f (no io.mycat imports)"
    }
}
