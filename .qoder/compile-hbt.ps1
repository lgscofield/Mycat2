$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location "D:\Workspaces\IdeaProjects\Mycat2"
# Install parent POM for dependency management
mvn install -N -DskipTests -q
# Install calcite-core (Mycat fork) - needed because RexImpTable was modified
mvn install -pl calcite-core -DskipTests -q 2>&1 | Out-Null
# Compile hbt with all dependencies using -am
mvn compile -pl hbt -am -DskipTests 2>&1 | Out-File "D:\Workspaces\IdeaProjects\Mycat2\.qoder\compile-round18.txt" -Encoding UTF8
