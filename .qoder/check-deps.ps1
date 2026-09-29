$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location "D:\Workspaces\IdeaProjects\Mycat2"
mvn dependency:tree -pl mycat2 -Dincludes="*calcite*,*avatica*" 2>&1 | Out-File "D:\Workspaces\IdeaProjects\Mycat2\.qoder\dep-tree-mycat2.txt" -Encoding UTF8
