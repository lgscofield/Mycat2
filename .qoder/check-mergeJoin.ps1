$env:JAVA_HOME = "D:\Program Files\BellSoft\LibericaJDK-21-Full"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
javap -cp "D:\Nexus\repository\org\apache\calcite\calcite-linq4j\1.42.0\calcite-linq4j-1.42.0.jar" -public org.apache.calcite.linq4j.EnumerableDefaults 2>&1 | Select-String 'mergeJoin'
