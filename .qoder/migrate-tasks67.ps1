# Phase 1 Tasks 6+7: Move swapbuffer/ and util/packet/ to protocol
$root = 'D:\Workspaces\IdeaProjects\Mycat2'

# ===== Task 6: swapbuffer/ → protocol/swapbuffer/ =====
Write-Output "=== Task 6: swapbuffer/ ==="
$src = Join-Path $root 'common\src\main\java\io\mycat\swapbuffer'
$dst = Join-Path $root 'protocol\src\main\java\io\mycat\protocol\swapbuffer'
$files = Get-ChildItem (Join-Path $src '*.java') -File
Write-Output "Moving $($files.Count) swapbuffer files..."
foreach ($f in $files) {
    git -C $root mv $f.FullName (Join-Path $dst $f.Name) 2>&1 | Out-Null
}

# Update package declarations
Get-ChildItem (Join-Path $dst '*.java') -File | ForEach-Object {
    $c = Get-Content $_.FullName -Raw -Encoding UTF8
    $c = $c -replace 'package io\.mycat\.swapbuffer;', 'package io.mycat.protocol.swapbuffer;'
    Set-Content -Path $_.FullName -Value $c -Encoding UTF8 -NoNewline
}

# Update all import statements across project
$count = 0
Get-ChildItem -Path $root -Recurse -Filter '*.java' | Where-Object { $_.FullName -notmatch '\\\.git\\' -and $_.FullName -notmatch '\\\.qoder\\' -and $_.FullName -notmatch '\\\.superpowers\\' } | ForEach-Object {
    $c = Get-Content $_.FullName -Raw -Encoding UTF8
    if ($c -match 'io\.mycat\.swapbuffer\.') {
        $n = $c -replace 'import io\.mycat\.swapbuffer\.', 'import io.mycat.protocol.swapbuffer.'
        if ($n -ne $c) {
            Set-Content -Path $_.FullName -Value $n -Encoding UTF8 -NoNewline
            $count++
        }
    }
}
Write-Output "  Updated imports in $count files"

# ===== Task 7: util/packet/ → protocol/codec/ =====
Write-Output "=== Task 7: util/packet/ ==="
$src2 = Join-Path $root 'common\src\main\java\io\mycat\util\packet'
$dst2 = Join-Path $root 'protocol\src\main\java\io\mycat\protocol\codec'
$files2 = Get-ChildItem (Join-Path $src2 '*.java') -File
Write-Output "Moving $($files2.Count) util/packet files..."
foreach ($f in $files2) {
    git -C $root mv $f.FullName (Join-Path $dst2 $f.Name) 2>&1 | Out-Null
}

# Update package declarations for moved files
foreach ($f in $files2) {
    $target = Join-Path $dst2 $f.Name
    $c = Get-Content $target -Raw -Encoding UTF8
    $c = $c -replace 'package io\.mycat\.util\.packet;', 'package io.mycat.protocol.codec;'
    Set-Content -Path $target -Value $c -Encoding UTF8 -NoNewline
}

# Update all import statements across project
$count2 = 0
Get-ChildItem -Path $root -Recurse -Filter '*.java' | Where-Object { $_.FullName -notmatch '\\\.git\\' -and $_.FullName -notmatch '\\\.qoder\\' -and $_.FullName -notmatch '\\\.superpowers\\' } | ForEach-Object {
    $c = Get-Content $_.FullName -Raw -Encoding UTF8
    if ($c -match 'io\.mycat\.util\.packet\.') {
        $n = $c -replace 'import io\.mycat\.util\.packet\.', 'import io.mycat.protocol.codec.'
        if ($n -ne $c) {
            Set-Content -Path $_.FullName -Value $n -Encoding UTF8 -NoNewline
            $count2++
        }
    }
}
Write-Output "  Updated imports in $count2 files"

# ===== Remove BOM from all modified files =====
Write-Output "=== Removing BOM ==="
$bomCount = 0
Get-ChildItem -Path $root -Recurse -Filter '*.java' | Where-Object { $_.FullName -notmatch '\\\.git\\' -and $_.FullName -notmatch '\\\.qoder\\' } | ForEach-Object {
    $b = [System.IO.File]::ReadAllBytes($_.FullName)
    if ($b.Length -ge 3 -and $b[0] -eq 0xEF -and $b[1] -eq 0xBB -and $b[2] -eq 0xBF) {
        [System.IO.File]::WriteAllBytes($_.FullName, [byte[]]$b[3..($b.Length-1)])
        $bomCount++
    }
}
Write-Output "  BOM removed from $bomCount files"

Write-Output "`nDone. Next: compile and commit."
