$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location "D:\Workspaces\IdeaProjects\Mycat2"
mvn install -N -DskipTests -q
mvn compile -DskipTests 2>&1 | Out-File "D:\Workspaces\IdeaProjects\Mycat2\.qoder\compile-full4.txt" -Encoding UTF8
