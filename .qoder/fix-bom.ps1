$filePath = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\util\RxBuiltInMethod.java"
$content = Get-Content $filePath -Encoding UTF8 -Raw
# Remove BOM if present
if ($content.Length -gt 0 -and $content[0] -eq [char]0xFEFF) {
    $content = $content.Substring(1)
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($filePath, $content, $utf8NoBom)
Write-Host "Fixed BOM"
# Verify first bytes
$bytes = [System.IO.File]::ReadAllBytes($filePath)
Write-Host "First 3 bytes: $($bytes[0]) $($bytes[1]) $($bytes[2])"
Write-Host "First line: $(Get-Content $filePath -TotalCount 1)"
