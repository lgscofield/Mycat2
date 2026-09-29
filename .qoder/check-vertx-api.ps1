$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$javap = "$env:JAVA_HOME\bin\javap"
$vertxJar = "D:\Nexus\repository\io\vertx\vertx-mysql-client\4.5.14\vertx-mysql-client-4.5.14.jar"

Write-Host "=== ColumnDefinition ==="
& $javap -classpath $vertxJar -public "io.vertx.mysqlclient.impl.protocol.ColumnDefinition"
