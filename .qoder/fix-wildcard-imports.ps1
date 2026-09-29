# Fix wildcard imports that need explicit imports for moved view interfaces
$root = 'D:\Workspaces\IdeaProjects\Mycat2'
$count = 0

Get-ChildItem -Path $root -Recurse -Filter '*.java' | Where-Object {
    $_.FullName -notmatch '\\\.git\\' -and $_.FullName -notmatch '\\\.qoder\\' -and $_.FullName -notmatch '\\protocol\\'
} | ForEach-Object {
    $content = Get-Content $_.FullName -Raw -Encoding UTF8
    if ($content -match 'import io\.mycat\.beans\.mysql\.packet\.\*' -and ($content -match 'MySQLPayloadWriteView|MySQLPayloadReadView')) {
        $oldImport = 'import io.mycat.beans.mysql.packet.*;'
        $newImports = "import io.mycat.beans.mysql.packet.*;`r`nimport io.mycat.protocol.mysql.packet.MySQLPayloadWriteView;`r`nimport io.mycat.protocol.mysql.packet.MySQLPayloadReadView;"
        $newContent = $content.Replace($oldImport, $newImports)
        if ($newContent -ne $content) {
            Set-Content -Path $_.FullName -Value $newContent -Encoding UTF8 -NoNewline
            $count++
            Write-Output "Added explicit imports: $($_.Name)"
        }
    }
}
Write-Output "Total files with added imports: $count"
