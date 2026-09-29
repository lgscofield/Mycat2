$base = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite"
$files = Get-ChildItem -Path $base -Recurse -Filter "*.java"
Write-Host "Total fork files: $($files.Count)"
foreach ($f in $files) {
    $rel = $f.FullName.Substring($base.Length + 1)
    $hasImport = (Get-Content $f.FullName -Raw) -match "import\s+io\.mycat"
    if ($hasImport) {
        Write-Host "BRIDGE: $rel"
    } else {
        Write-Host "DELETE: $rel"
    }
}
