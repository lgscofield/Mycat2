$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Verify JDK
& java -version 2>&1 | ForEach-Object { Write-Host $_ }

# Compile
cd "D:\Workspaces\IdeaProjects\Mycat2"
& mvn compile -pl calcite-core -am 2>&1 | Select-Object -Last 50 | ForEach-Object { Write-Host $_ }
