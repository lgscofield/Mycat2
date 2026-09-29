# SDD ledger — plan: docs/superpowers/plans/2026-09-28-phase0-jdk21-compilation.md

BASE: 2a0d21d8e6db64d87490fe1c2eebd42f368cca8c
Branch: feature/jdk21-upgrade
Note: IDE uses JDK 8, mvn compile with release=21 requires JDK 21 installed

Task 1: complete (commits 2a0d21d..074f460, review clean)
  - Upgraded maven-compiler-plugin 2.3.2→3.14.1, source/target→release=21
  - Removed compiler plugin overrides in hbt/pom.xml and migrate/pom.xml

Task 2: complete (commits 074f460..c5395e4, review clean)
  - Added comprehensive <properties> with all target versions
  - Added <dependencyManagement> with 50+ managed dependencies
  - Updated surefire-plugin 3.0.0-M5→3.5.3

Task 3: complete (commit c5395e4..c153da1)
  - Removed hardcoded lombok 1.18.10 from hbt/pom.xml
  - Root POM dependencyManagement now provides 1.18.42

Tasks 4-7: complete (commit c153da1..bcd154c)
  - Task 4: Added --add-exports for sun.nio.ch.DirectBuffer
  - Task 5: Removed unused com.sun.glass.events import from UIMain.java
  - Task 6: Replaced Thread.stop() with interrupt() in IOExecutor.java
  - Task 7: Removed SecurityManager from NameableThreadFactory.java

Tasks 8-14: complete (commit bcd154c..4663dd8)
  - Removed ~100 hardcoded version declarations from 16 sub-module pom.xml files
  - Migrated mysql:mysql-connector-java→com.mysql:mysql-connector-j in 5 modules
  - Added missing deps to root POM dependencyManagement (jackson-dataformat-yaml, vertx-jdbc-client, arrow-algorithm, arrow-dataset)

Task 17: complete (commit 4663dd8..f6d4a1b)
  - Rewrote Dockerfile: mysql:8.0.16+JDK8 → bellsoft/liberica-openjdk-debian:21

Tasks 15, 16, 18: PENDING (require JDK 21 for verification)
  - Task 15: javax→jakarta import migration (~52 files)
  - Task 16: Vert.x 4.2.5→4.5.14 internal API migration (~80 files)
  - Task 18: Final full compilation verification

