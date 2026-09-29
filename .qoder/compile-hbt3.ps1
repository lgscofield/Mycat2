$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
cd "D:\Workspaces\IdeaProjects\Mycat2"
& mvn compile -pl hbt -am -DskipTests 2>&1 | ForEach-Object { Write-Host $_ }
