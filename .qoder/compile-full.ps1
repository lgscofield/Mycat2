$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location "D:\Workspaces\IdeaProjects\Mycat2"
# Install parent POM
mvn install -N -DskipTests -q
# Full project compile
mvn compile -DskipTests 2>&1 | Out-File "D:\Workspaces\IdeaProjects\Mycat2\.qoder\compile-full.txt" -Encoding UTF8
