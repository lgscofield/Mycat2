$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
cd "D:\Workspaces\IdeaProjects\Mycat2"
& mvn compile -pl hbt -DskipTests 2>&1 | Out-File "D:\Workspaces\IdeaProjects\Mycat2\hbt-compile2.txt" -Encoding UTF8
Write-Host "DONE - exit code: $LASTEXITCODE"
