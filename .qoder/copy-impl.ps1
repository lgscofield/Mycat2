$hbtBase = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite"
$forkBase = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java\org\apache\calcite"

# Copy MycatBuiltInMethodImpl
$src = Join-Path $hbtBase "mycat\MycatBuiltInMethodImpl.java"
$dest = Join-Path $forkBase "mycat\MycatBuiltInMethodImpl.java"
Copy-Item $src $dest -Force
Write-Host "Copied MycatBuiltInMethodImpl.java"
