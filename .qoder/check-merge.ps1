$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
javap -cp "D:\Nexus\repository\com\alibaba\druid\1.2.28\druid-1.2.28.jar" -public com.alibaba.druid.sql.ast.statement.SQLMergeStatement 2>&1 | Select-String 'class|Clause'
