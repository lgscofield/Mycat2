# Phase 1 Task 3: Move beans/mysql/ (28 files) from common to protocol
# Reverses dependency: protocol becomes leaf, common depends on protocol

$root = 'D:\Workspaces\IdeaProjects\Mycat2'
$src = Join-Path $root 'common\src\main\java\io\mycat\beans\mysql'
$dst = Join-Path $root 'protocol\src\main\java\io\mycat\protocol\mysql\type'

# Step 1: Move 28 files
$files = Get-ChildItem (Join-Path $src '*.java') -File
Write-Output "Moving $($files.Count) files from beans/mysql/ to protocol/mysql/type/"
foreach ($f in $files) {
    $target = Join-Path $dst $f.Name
    git -C $root mv $f.FullName $target 2>&1 | Out-Null
    Write-Output "  git mv $($f.Name)"
}

# Step 2: Move 3 utility files needed by beans/mysql/ but not by rest of common
$utilFiles = @('ByteArrayOutput.java', 'NameMap.java', 'CaseInsensitiveComparator.java')
$codecDst = Join-Path $root 'protocol\src\main\java\io\mycat\protocol\codec'
foreach ($uf in $utilFiles) {
    $srcPath = Join-Path $root "common\src\main\java\io\mycat\util\$uf"
    if (Test-Path $srcPath) {
        $target = Join-Path $codecDst $uf
        git -C $root mv $srcPath $target 2>&1 | Out-Null
        Write-Output "  git mv util/$uf -> protocol/codec/"
    }
}

# Step 3: Update package declarations in moved files
Write-Output "`nUpdating package declarations..."

# beans/mysql/ -> protocol/mysql/type/
Get-ChildItem (Join-Path $dst '*.java') -File | ForEach-Object {
    $content = Get-Content $_.FullName -Raw -Encoding UTF8
    $content = $content -replace 'package io\.mycat\.beans\.mysql;', 'package io.mycat.protocol.mysql.type;'
    Set-Content -Path $_.FullName -Value $content -Encoding UTF8 -NoNewline
}

# util -> protocol/codec
foreach ($uf in $utilFiles) {
    $target = Join-Path $codecDst $uf
    if (Test-Path $target) {
        $content = Get-Content $target -Raw -Encoding UTF8
        $content = $content -replace 'package io\.mycat\.util;', 'package io.mycat.protocol.codec;'
        Set-Content -Path $target -Value $content -Encoding UTF8 -NoNewline
    }
}

# Step 4: Update ALL import statements across the entire project
Write-Output "`nUpdating import statements across project..."
$javaFiles = Get-ChildItem -Path $root -Recurse -Filter '*.java' | Where-Object { $_.FullName -notmatch '\\\.git\\' -and $_.FullName -notmatch '\\\.qoder\\' -and $_.FullName -notmatch '\\\.superpowers\\' }
$count = 0
foreach ($jf in $javaFiles) {
    $content = Get-Content $jf.FullName -Raw -Encoding UTF8
    if ($content -match 'io\.mycat\.beans\.mysql\.[A-Z]|io\.mycat\.util\.(ByteArrayOutput|NameMap|CaseInsensitiveComparator)') {
        $newContent = $content -replace 'import io\.mycat\.beans\.mysql\.([A-Z])', 'import io.mycat.protocol.mysql.type.$1'
        $newContent = $newContent -replace 'import io\.mycat\.util\.ByteArrayOutput;', 'import io.mycat.protocol.codec.ByteArrayOutput;'
        $newContent = $newContent -replace 'import io\.mycat\.util\.NameMap;', 'import io.mycat.protocol.codec.NameMap;'
        $newContent = $newContent -replace 'import io\.mycat\.util\.CaseInsensitiveComparator;', 'import io.mycat.protocol.codec.CaseInsensitiveComparator;'
        if ($newContent -ne $content) {
            Set-Content -Path $jf.FullName -Value $newContent -Encoding UTF8 -NoNewline
            $count++
        }
    }
}
Write-Output "  Updated imports in $count files"

Write-Output "`nDone. Next: update POM files manually, then compile."
