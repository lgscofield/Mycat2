$filePath = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\util\RxBuiltInMethod.java"
$content = Get-Content $filePath -Encoding Unicode -Raw
[System.IO.File]::WriteAllText($filePath, $content, [System.Text.Encoding]::UTF8)
Write-Host "Converted to UTF-8"
# Verify
$firstLine = Get-Content $filePath -TotalCount 3
$firstLine | ForEach-Object { Write-Host $_ }
