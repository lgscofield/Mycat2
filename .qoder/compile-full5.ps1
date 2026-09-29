$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location "D:\Workspaces\IdeaProjects\Mycat2"
mvn install -N -DskipTests -q
mvn compile -DskipTests -rf :mycat2 2>&1 | Out-File "D:\Workspaces\IdeaProjects\Mycat2\.qoder\compile-full5.txt" -Encoding UTF8
