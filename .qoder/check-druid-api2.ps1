$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host "=== SQLMergeStatement.When ==="
javap -cp "D:\Nexus\repository\com\alibaba\druid\1.2.28\druid-1.2.28.jar" -public 'com.alibaba.druid.sql.ast.statement.SQLMergeStatement$When'
Write-Host ""
Write-Host "=== SQLMergeStatement full ==="
javap -cp "D:\Nexus\repository\com\alibaba\druid\1.2.28\druid-1.2.28.jar" -public com.alibaba.druid.sql.ast.statement.SQLMergeStatement
Write-Host ""
Write-Host "=== OracleSelectPivotBase ==="
javap -cp "D:\Nexus\repository\com\alibaba\druid\1.2.28\druid-1.2.28.jar" -public com.alibaba.druid.sql.dialect.oracle.ast.stmt.OracleSelectPivotBase
