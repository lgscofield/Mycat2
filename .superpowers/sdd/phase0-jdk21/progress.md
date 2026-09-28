# SDD ledger — plan: docs/superpowers/plans/2026-09-28-phase0-jdk21-compilation.md

BASE: 2a0d21d8e6db64d87490fe1c2eebd42f368cca8c
Branch: feature/jdk21-upgrade
Note: IDE uses JDK 8, mvn compile with release=21 requires JDK 21 installed

Task 1: complete (commits 2a0d21d..074f460, review clean)
  - Upgraded maven-compiler-plugin 2.3.2→3.14.1, source/target→release=21
  - Removed compiler plugin overrides in hbt/pom.xml and migrate/pom.xml

