# Add explicit imports for view interfaces moved to protocol
$root = 'D:\Workspaces\IdeaProjects\Mycat2'
$packetDir = Join-Path $root 'common\src\main\java\io\mycat\beans\mysql\packet'
$count = 0

Get-ChildItem -Path $packetDir -Filter '*.java' | ForEach-Object {
    $content = Get-Content $_.FullName -Raw -Encoding UTF8
    $needsWrite = $content -match 'MySQLPayloadWriteView'
    $needsRead = $content -match 'MySQLPayloadReadView'
    
    if ($needsWrite -or $needsRead) {
        $importsToAdd = ""
        if ($needsWrite -and $content -notmatch 'import io\.mycat\.protocol\.mysql\.packet\.MySQLPayloadWriteView') {
            $importsToAdd += "`r`nimport io.mycat.protocol.mysql.packet.MySQLPayloadWriteView;"
        }
        if ($needsRead -and $content -notmatch 'import io\.mycat\.protocol\.mysql\.packet\.MySQLPayloadReadView') {
            $importsToAdd += "`r`nimport io.mycat.protocol.mysql.packet.MySQLPayloadReadView;"
        }
        
        if ($importsToAdd) {
            # Insert after package declaration
            $newContent = $content -replace '(package [^;]+;)', "`$1$importsToAdd"
            Set-Content -Path $_.FullName -Value $newContent -Encoding UTF8 -NoNewline
            $count++
            Write-Output "Added imports to: $($_.Name)"
        }
    }
}
Write-Output "Total: $count files"
