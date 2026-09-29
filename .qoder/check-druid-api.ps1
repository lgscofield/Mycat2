$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Write-Host "=== SQLMergeStatement full API ==="
javap -cp "D:\Nexus\repository\com\alibaba\druid\1.2.28\druid-1.2.28.jar" -public com.alibaba.druid.sql.ast.statement.SQLMergeStatement
Write-Host ""
Write-Host "=== OracleSelectPivot ==="
jar -tf "D:\Nexus\repository\com\alibaba\druid\1.2.28\druid-1.2.28.jar" | Select-String 'OracleSelectPivot|Pivot'
