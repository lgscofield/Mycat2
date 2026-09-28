# Calcite-Core Fork Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a `calcite-core` Maven fork module (`io.mycat:calcite-core:1.42.0-mycat`) containing Calcite 1.42.0 full source with minimal Mycat customizations, then rewire `hbt` to depend on it.

**Architecture:** Extract all 1646 Calcite 1.42.0 source files into a new `calcite-core/` Maven submodule. Overlay ~10 files with Mycat-specific modifications (annotated with `MYCAT CHANGE` markers). Add 5 new Mycat classes. Delete all 68 old fork files from `hbt`, re-add 16 bridge files (that import `io.mycat`), and switch `hbt`'s dependency from `org.apache.calcite:calcite-core` to `io.mycat:calcite-core:1.42.0-mycat`.

**Tech Stack:** Maven, Calcite 1.42.0, Immutables 2.10.1, Lombok 1.18.42, JDK 21

## Global Constraints

- JDK 21 target (`<source>21</source><target>21</target>`)
- calcite-core fork version: `1.42.0-mycat`, groupId `io.mycat`
- Parent POM: `io.mycat:parent:1.22-release`
- Immutables: `org.immutables:value:2.10.1` (annotation processor)
- calcite-linq4j: `org.apache.calcite:calcite-linq4j:1.42.0`
- avatica-core: `org.apache.calcite.avatica:avatica-core:1.26.0`
- Calcite source JAR: `D:\Nexus\repository\org\apache\calcite\calcite-core\1.42.0\calcite-core-1.42.0-sources.jar`
- **No `io.mycat` dependencies in calcite-core module** (DAG constraint)
- Comment convention: `MYCAT FORK NOTICE` file headers + `MYCAT CHANGE` inline markers (see spec)
- Mycat parent version properties: `calcite.version=1.42.0`, `immutables.version=2.10.1`, `avatica.version=1.26.0`

---

## File Structure

### New files to create
| File | Responsibility |
|------|---------------|
| `calcite-core/pom.xml` | Maven module config for fork |
| `calcite-core/src/main/java/org/apache/calcite/...` | 1646 files from Calcite 1.42.0 sources JAR |

### Files modified in fork (Mycat customizations overlaid on 1.42.0 baseline)
| File | Modification |
|------|-------------|
| `adapter/enumerable/RexImpTable.java` | Register Mycat custom function implementations |
| `sql/validate/implicit/AbstractTypeCoercion.java` | MySQL type coercion rules |
| `sql/fun/SqlCastFunction.java` | MySQL CAST behavior |
| `sql/SqlLiteral.java` | Mycat literal extensions |
| `config/CalciteSystemProperty.java` | MySQL-related config properties |
| `prepare/CalciteCatalogReader.java` | Custom catalog reading |
| `rel/rules/JoinToMultiJoinRule.java` | Custom JOIN clustering |
| `adapter/enumerable/EnumerableRelImplementor.java` | Mycat execution extensions |
| `adapter/enumerable/PhysTypeImpl.java` | Mycat physical type support |
| `adapter/enumerable/EnumUtils.java` | Mycat enumerable utilities |
| `runtime/Bindable.java` | Extended bindable interface |
| `linq4j/EnumerableDefaults.java` | Extended linq4j defaults |
| `avatica/util/TimeUnitRange.java` | Time unit range extension |

### New files added to fork (Mycat additions, not in Calcite 1.42.0)
| File | Purpose |
|------|---------|
| `mycat/MycatBuiltInMethod.java` | Mycat type conversion method enums |
| `mycat/MycatSqlDefinedFunction.java` | Mycat custom SQL function base |
| `sql/SqlForUpdate.java` | FOR UPDATE clause support |
| `sql/MysqlExactNumericLiteral.java` | MySQL exact numeric literal |
| `util/RxBuiltInMethod.java` | RxJava built-in method refs |

### Files modified in parent POM
| File | Change |
|------|--------|
| `pom.xml` (root) | Add `<module>calcite-core</module>`, add dependencyManagement entry |

### Files modified in hbt
| File | Change |
|------|--------|
| `hbt/pom.xml` | Switch `org.apache.calcite:calcite-core` → `io.mycat:calcite-core:1.42.0-mycat` |

### Files deleted from hbt (68 total, then 16 re-added)
All 68 files under `hbt/src/main/java/org/apache/calcite/` are deleted, then 16 bridge files (with `io.mycat` imports) are re-added.

---

### Task 1: Create calcite-core Maven Module Skeleton

**Files:**
- Create: `calcite-core/pom.xml`
- Modify: `pom.xml` (root):8-27 (modules section)

**Interfaces:**
- Produces: `calcite-core/` directory with valid POM that parent recognizes as submodule

- [ ] **Step 1: Create calcite-core directory**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
New-Item -ItemType Directory -Path "calcite-core\src\main\java" -Force
New-Item -ItemType Directory -Path "calcite-core\src\main\resources" -Force
```

- [ ] **Step 2: Create calcite-core/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <parent>
        <artifactId>parent</artifactId>
        <groupId>io.mycat</groupId>
        <version>1.22-release</version>
    </parent>
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.mycat</groupId>
    <artifactId>calcite-core</artifactId>
    <version>1.42.0-mycat</version>
    <name>calcite-core (Mycat fork)</name>
    <description>
        Calcite 1.42.0 fork with minimal Mycat customizations.
        See MYCAT FORK NOTICE headers and MYCAT CHANGE markers for modifications.
    </description>

    <properties>
        <calcite.upstream.version>1.42.0</calcite.upstream.version>
    </properties>

    <dependencies>
        <!-- Calcite linq4j (upstream) -->
        <dependency>
            <groupId>org.apache.calcite</groupId>
            <artifactId>calcite-linq4j</artifactId>
            <version>${calcite.version}</version>
        </dependency>

        <!-- Avatica -->
        <dependency>
            <groupId>org.apache.calcite.avatica</groupId>
            <artifactId>avatica-core</artifactId>
            <version>${avatica.version}</version>
        </dependency>

        <!-- JTS (spatial) -->
        <dependency>
            <groupId>org.locationtech.jts</groupId>
            <artifactId>jts-core</artifactId>
            <version>1.19.0</version>
        </dependency>
        <dependency>
            <groupId>org.locationtech.jts.io</groupId>
            <artifactId>jts-io-common</artifactId>
            <version>1.19.0</version>
        </dependency>
        <dependency>
            <groupId>org.locationtech.proj4j</groupId>
            <artifactId>proj4j</artifactId>
            <version>1.2.2</version>
        </dependency>

        <!-- Jackson -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-annotations</artifactId>
        </dependency>

        <!-- Guava -->
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
        </dependency>

        <!-- Error Prone annotations -->
        <dependency>
            <groupId>com.google.errorprone</groupId>
            <artifactId>error_prone_annotations</artifactId>
            <version>2.36.0</version>
        </dependency>

        <!-- API Guardian -->
        <dependency>
            <groupId>org.apiguardian</groupId>
            <artifactId>apiguardian-api</artifactId>
            <version>1.1.2</version>
        </dependency>

        <!-- Checker Qual -->
        <dependency>
            <groupId>org.checkerframework</groupId>
            <artifactId>checker-qual</artifactId>
            <version>3.10.0</version>
        </dependency>

        <!-- SLF4J -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>1.7.25</version>
        </dependency>

        <!-- Runtime dependencies from upstream Calcite 1.42.0 -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-core</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-yaml</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.google.uzaygezen</groupId>
            <artifactId>uzaygezen-core</artifactId>
            <version>0.2</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.jayway.jsonpath</groupId>
            <artifactId>json-path</artifactId>
            <version>2.10.0</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.yahoo.datasketches</groupId>
            <artifactId>sketches-core</artifactId>
            <version>0.9.0</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>commons-codec</groupId>
            <artifactId>commons-codec</artifactId>
            <version>1.16.0</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>net.hydromatic</groupId>
            <artifactId>aggdesigner-algorithm</artifactId>
            <version>6.1</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-dbcp2</artifactId>
            <version>2.11.0</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-math3</artifactId>
            <version>3.6.1</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-text</artifactId>
            <version>1.11.0</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.jooq</groupId>
            <artifactId>joou-java-6</artifactId>
            <version>0.9.5</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>commons-io</groupId>
            <artifactId>commons-io</artifactId>
            <version>2.15.0</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.codehaus.janino</groupId>
            <artifactId>commons-compiler</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.codehaus.janino</groupId>
            <artifactId>janino</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- Immutables: annotation processor for @Value.Immutable Config pattern -->
        <dependency>
            <groupId>org.immutables</groupId>
            <artifactId>value</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <source>21</source>
                    <target>21</target>
                    <encoding>UTF-8</encoding>
                    <compilerArgs>
                        <arg>--add-exports</arg>
                        <arg>java.base/sun.nio.ch=ALL-UNNAMED</arg>
                    </compilerArgs>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.immutables</groupId>
                            <artifactId>value</artifactId>
                            <version>${immutables.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Add calcite-core to parent POM modules**

In root `pom.xml`, add `<module>calcite-core</module>` BEFORE the `<module>hbt</module>` line (build order: calcite-core must compile before hbt):

```xml
<!-- Change from: -->
      <module>hbt</module>

<!-- Change to: -->
      <module>calcite-core</module>
      <module>hbt</module>
```

- [ ] **Step 4: Add calcite-core to parent POM dependencyManagement**

In root `pom.xml` `<dependencyManagement>`, add entry after the existing `org.apache.calcite:calcite-core` entry:

```xml
      <!-- Mycat Calcite fork (replaces org.apache.calcite:calcite-core for hbt) -->
      <dependency>
        <groupId>io.mycat</groupId>
        <artifactId>calcite-core</artifactId>
        <version>1.42.0-mycat</version>
      </dependency>
```

- [ ] **Step 5: Verify module is recognized**

Run:
```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn help:effective-pom -pl calcite-core -N
```
Expected: POM resolves without errors, parent is `io.mycat:parent:1.22-release`.

- [ ] **Step 6: Commit**

```bash
git add calcite-core/pom.xml pom.xml
git commit -m "feat: add calcite-core fork module skeleton (1.42.0-mycat)"
```

---

### Task 2: Extract Calcite 1.42.0 Sources

**Files:**
- Create: `calcite-core/src/main/java/org/apache/calcite/...` (1646 files)
- Create: `calcite-core/src/main/resources/META-INF/LICENSE`
- Create: `calcite-core/src/main/resources/META-INF/NOTICE`

**Interfaces:**
- Consumes: `D:\Nexus\repository\org\apache\calcite\calcite-core\1.42.0\calcite-core-1.42.0-sources.jar`
- Produces: Complete Calcite 1.42.0 source tree in `calcite-core/src/main/java/`

- [ ] **Step 1: Extract source JAR**

```powershell
$jarPath = "D:\Nexus\repository\org\apache\calcite\calcite-core\1.42.0\calcite-core-1.42.0-sources.jar"
$destPath = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java"
$tempExtract = Join-Path $env:TEMP "calcite-1.42-extract"

# Clean temp and extract
if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
New-Item -ItemType Directory -Path $tempExtract -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($jarPath, $tempExtract)

# Copy only Java source files (exclude META-INF)
Get-ChildItem -Path $tempExtract -Recurse -Filter "*.java" | ForEach-Object {
    $relPath = $_.FullName.Substring($tempExtract.Length + 1)
    $destFile = Join-Path $destPath $relPath
    $destDir = Split-Path $destFile -Parent
    if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
    Copy-Item $_.FullName $destFile -Force
}

Write-Host "Extracted $((Get-ChildItem -Path $destPath -Recurse -Filter '*.java' | Measure-Object).Count) Java files"
```

- [ ] **Step 2: Copy META-INF resources**

```powershell
$resDest = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\resources\META-INF"
New-Item -ItemType Directory -Path $resDest -Force | Out-Null
$tempExtract = Join-Path $env:TEMP "calcite-1.42-extract"
Copy-Item (Join-Path $tempExtract "META-INF\LICENSE") $resDest -Force
Copy-Item (Join-Path $tempExtract "META-INF\NOTICE") $resDest -Force
```

- [ ] **Step 3: Verify file count**

```powershell
$count = (Get-ChildItem -Path "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java" -Recurse -Filter "*.java" | Measure-Object).Count
Write-Host "Total Java files: $count"
if ($count -eq 1646) { Write-Host "OK: matches expected 1646 files" } else { Write-Host "WARNING: expected 1646, got $count" }
```

- [ ] **Step 4: Verify key files exist**

```powershell
$checks = @(
    "calcite-core\src\main\java\org\apache\calcite\package-info.java",
    "calcite-core\src\main\java\org\apache\calcite\sql\SqlLiteral.java",
    "calcite-core\src\main\java\org\apache\calcite\adapter\enumerable\RexImpTable.java",
    "calcite-core\src\main\java\org\apache\calcite\config\CalciteSystemProperty.java"
)
foreach ($f in $checks) {
    $full = "D:\Workspaces\IdeaProjects\Mycat2\$f"
    if (Test-Path $full) { Write-Host "OK: $f" } else { Write-Host "MISSING: $f" }
}
```

- [ ] **Step 5: Commit**

```bash
git add calcite-core/src/
git commit -m "feat: extract Calcite 1.42.0 sources (1646 files) into calcite-core module"
```

---

### Task 3: Verify Clean Calcite Compilation

**Files:**
- None modified (verification only)

**Interfaces:**
- Consumes: Task 1 (POM) + Task 2 (sources)
- Produces: Verified clean compilation baseline

- [ ] **Step 1: Compile calcite-core module**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile -pl calcite-core -am
```
Expected: `BUILD SUCCESS`. This validates that unmodified Calcite 1.42.0 compiles under JDK 21 with our annotation processor config.

- [ ] **Step 2: If compilation fails, diagnose and fix**

Common issues:
- **Missing dependency**: Add to `calcite-core/pom.xml` dependencies section
- **Annotation processor error**: Check `annotationProcessorPaths` config matches `org.immutables:value:2.10.1`
- **JDK 21 module access**: Add `--add-exports` or `--add-opens` to `compilerArgs`

After each fix, re-run:
```powershell
mvn compile -pl calcite-core
```

- [ ] **Step 3: Record Immutables-generated classes**

```powershell
$generated = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\target\generated-sources"
if (Test-Path $generated) {
    $count = (Get-ChildItem -Path $generated -Recurse -Filter "*.java" | Measure-Object).Count
    Write-Host "Generated sources: $count files"
} else {
    Write-Host "WARNING: No generated-sources directory found"
}
```

- [ ] **Step 4: Commit any fixes**

```bash
git add calcite-core/pom.xml
git commit -m "fix: resolve calcite-core clean compilation issues"
```

---

### Task 4: Apply Mycat Customizations — RexImpTable

**Files:**
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java`
- Reference: `hbt/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java` (old fork)

**Interfaces:**
- Consumes: Calcite 1.42.0 baseline `RexImpTable.java` (already extracted in Task 2)
- Produces: Modified `RexImpTable.java` with Mycat function registrations

- [ ] **Step 1: Diff old fork against 1.42.0 baseline**

Compare `hbt/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java` with `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java`. Identify which differences are Mycat-specific (not just 1.26→1.42 version drift).

Key Mycat changes to look for:
- Registration of `MycatBuiltInMethod` implementations
- Custom `REGEXP_REPLACE_2`/`REGEXP_REPLACE_3` function mappings
- Any `MycatBuiltInMethodImpl` references

- [ ] **Step 2: Apply Mycat changes to 1.42.0 baseline**

Add `MYCAT FORK NOTICE` header at top of file:
```java
/*
 * MYCAT FORK NOTICE
 * Base: Calcite 1.42.0 (org.apache.calcite:calcite-core:1.42.0)
 * Purpose: Register Mycat custom function implementations (MycatBuiltInMethod, REGEXP_REPLACE variants)
 * Changes:
 *   - Added MycatBuiltInMethod imports and method registrations
 *   - Added REGEXP_REPLACE_2/REGEXP_REPLACE_3 function mappings
 * Last reviewed against Calcite version: 1.42.0
 */
```

At each modification point, add inline marker:
```java
// MYCAT CHANGE: Register Mycat custom type conversion functions
// Original (Calcite 1.42.0): [description of what was here]
```

- [ ] **Step 3: Compile and verify**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile -pl calcite-core
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/RexImpTable.java
git commit -m "feat: apply Mycat customizations to RexImpTable (function registrations)"
```

---

### Task 5: Apply Mycat Customizations — SQL Layer

**Files:**
- Modify: `calcite-core/src/main/java/org/apache/calcite/sql/validate/implicit/AbstractTypeCoercion.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/sql/fun/SqlCastFunction.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/sql/SqlLiteral.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/config/CalciteSystemProperty.java`
- Reference: Corresponding files under `hbt/src/main/java/org/apache/calcite/`

**Interfaces:**
- Consumes: Calcite 1.42.0 baselines (already extracted)
- Produces: 4 modified files with Mycat SQL-layer customizations

- [ ] **Step 1: Diff and apply — AbstractTypeCoercion.java**

Compare `hbt/src/main/java/org/apache/calcite/sql/validate/implicit/AbstractTypeCoercion.java` with `calcite-core/` version. Apply Mycat-specific changes (MySQL type coercion rules) with `MYCAT CHANGE` markers.

Key changes: MySQL-compatible type coercion, implicit conversion rules for MySQL compatibility.

- [ ] **Step 2: Diff and apply — SqlCastFunction.java**

Compare `hbt/src/main/java/org/apache/calcite/sql/fun/SqlCastFunction.java` with `calcite-core/` version. Apply Mycat-specific changes (MySQL CAST behavior) with `MYCAT CHANGE` markers.

- [ ] **Step 3: Diff and apply — SqlLiteral.java**

Compare `hbt/src/main/java/org/apache/calcite/sql/SqlLiteral.java` with `calcite-core/` version. Apply Mycat-specific changes with `MYCAT CHANGE` markers.

Key changes: `SqlTypeName.TIMESTAMP` handling instead of `boolean` parameter.

- [ ] **Step 4: Diff and apply — CalciteSystemProperty.java**

Compare `hbt/src/main/java/org/apache/calcite/config/CalciteSystemProperty.java` with `calcite-core/` version. Apply MySQL-related config properties with `MYCAT CHANGE` markers.

- [ ] **Step 5: Add MYCAT FORK NOTICE headers to all 4 files**

Each file gets the standard header:
```java
/*
 * MYCAT FORK NOTICE
 * Base: Calcite 1.42.0 (org.apache.calcite:calcite-core:1.42.0)
 * Purpose: [specific reason for this file]
 * Changes:
 *   - [change summary]
 * Last reviewed against Calcite version: 1.42.0
 */
```

- [ ] **Step 6: Compile and verify**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile -pl calcite-core
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add calcite-core/src/main/java/org/apache/calcite/sql/validate/implicit/AbstractTypeCoercion.java
git add calcite-core/src/main/java/org/apache/calcite/sql/fun/SqlCastFunction.java
git add calcite-core/src/main/java/org/apache/calcite/sql/SqlLiteral.java
git add calcite-core/src/main/java/org/apache/calcite/config/CalciteSystemProperty.java
git commit -m "feat: apply Mycat SQL-layer customizations (type coercion, CAST, literal, system props)"
```

---

### Task 6: Apply Mycat Customizations — Adapter & Plan Layer

**Files:**
- Modify: `calcite-core/src/main/java/org/apache/calcite/prepare/CalciteCatalogReader.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/rel/rules/JoinToMultiJoinRule.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/EnumerableRelImplementor.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/PhysTypeImpl.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/EnumUtils.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/runtime/Bindable.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/linq4j/EnumerableDefaults.java`
- Modify: `calcite-core/src/main/java/org/apache/calcite/avatica/util/TimeUnitRange.java`
- Reference: Corresponding files under `hbt/src/main/java/org/apache/calcite/`

**Interfaces:**
- Consumes: Calcite 1.42.0 baselines (already extracted)
- Produces: 8 modified files with Mycat adapter/plan customizations

- [ ] **Step 1: Diff and apply — CalciteCatalogReader.java**

Compare hbt fork with 1.42.0 baseline. Apply custom catalog reading logic with `MYCAT CHANGE` markers.

- [ ] **Step 2: Diff and apply — JoinToMultiJoinRule.java**

Compare hbt fork with 1.42.0 baseline. Apply custom JOIN clustering logic with `MYCAT CHANGE` markers.

- [ ] **Step 3: Diff and apply — EnumerableRelImplementor.java**

Compare hbt fork with 1.42.0 baseline. Apply Mycat execution extensions with `MYCAT CHANGE` markers.

- [ ] **Step 4: Diff and apply — PhysTypeImpl.java**

Compare hbt fork with 1.42.0 baseline. Apply Mycat physical type support with `MYCAT CHANGE` markers.

- [ ] **Step 5: Diff and apply — EnumUtils.java**

Compare hbt fork with 1.42.0 baseline. Apply Mycat enumerable utilities with `MYCAT CHANGE` markers.

- [ ] **Step 6: Diff and apply — Bindable.java**

Compare hbt fork with 1.42.0 baseline. Apply extended bindable interface with `MYCAT CHANGE` markers.

- [ ] **Step 7: Diff and apply — EnumerableDefaults.java**

Compare hbt fork with 1.42.0 baseline. Apply extended linq4j defaults with `MYCAT CHANGE` markers.

- [ ] **Step 8: Diff and apply — TimeUnitRange.java**

Compare hbt fork with 1.42.0 baseline. Apply time unit range extension with `MYCAT CHANGE` markers.

- [ ] **Step 9: Add MYCAT FORK NOTICE headers to all 8 files**

- [ ] **Step 10: Compile and verify**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile -pl calcite-core
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 11: Commit**

```bash
git add calcite-core/src/main/java/org/apache/calcite/prepare/CalciteCatalogReader.java
git add calcite-core/src/main/java/org/apache/calcite/rel/rules/JoinToMultiJoinRule.java
git add calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/EnumerableRelImplementor.java
git add calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/PhysTypeImpl.java
git add calcite-core/src/main/java/org/apache/calcite/adapter/enumerable/EnumUtils.java
git add calcite-core/src/main/java/org/apache/calcite/runtime/Bindable.java
git add calcite-core/src/main/java/org/apache/calcite/linq4j/EnumerableDefaults.java
git add calcite-core/src/main/java/org/apache/calcite/avatica/util/TimeUnitRange.java
git commit -m "feat: apply Mycat adapter/plan customizations (catalog, JOIN, enumerable, bindable, linq4j)"
```

---

### Task 7: Add Mycat New Files to Fork

**Files:**
- Create: `calcite-core/src/main/java/org/apache/calcite/mycat/MycatBuiltInMethod.java`
- Create: `calcite-core/src/main/java/org/apache/calcite/mycat/MycatSqlDefinedFunction.java`
- Create: `calcite-core/src/main/java/org/apache/calcite/sql/SqlForUpdate.java`
- Create: `calcite-core/src/main/java/org/apache/calcite/sql/MysqlExactNumericLiteral.java`
- Create: `calcite-core/src/main/java/org/apache/calcite/util/RxBuiltInMethod.java`

**Interfaces:**
- Consumes: Old fork files from `hbt/src/main/java/org/apache/calcite/` as reference
- Produces: 5 new files in calcite-core with `MYCAT ADDITION` headers

- [ ] **Step 1: Copy MycatBuiltInMethod.java from hbt**

```powershell
$src = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\mycat\MycatBuiltInMethod.java"
$dest = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java\org\apache\calcite\mycat\MycatBuiltInMethod.java"
New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
Copy-Item $src $dest -Force
```

Add `MYCAT ADDITION` header:
```java
/*
 * MYCAT ADDITION
 * This class does not exist in Calcite 1.42.0.
 * Purpose: Mycat type conversion method enum definitions
 * Reason for org.apache.calcite package: Must be co-located with Calcite's BuiltInMethod for package-private access
 */
```

- [ ] **Step 2: Copy MycatSqlDefinedFunction.java from hbt**

```powershell
$src = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\mycat\MycatSqlDefinedFunction.java"
$dest = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java\org\apache\calcite\mycat\MycatSqlDefinedFunction.java"
Copy-Item $src $dest -Force
```

Add `MYCAT ADDITION` header with purpose: Mycat custom SQL function base class.

- [ ] **Step 3: Copy SqlForUpdate.java from hbt**

```powershell
$src = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\sql\SqlForUpdate.java"
$dest = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java\org\apache\calcite\sql\SqlForUpdate.java"
Copy-Item $src $dest -Force
```

Add `MYCAT ADDITION` header with purpose: FOR UPDATE clause support.

- [ ] **Step 4: Copy MysqlExactNumericLiteral.java from hbt**

```powershell
$src = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\sql\MysqlExactNumericLiteral.java"
$dest = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java\org\apache\calcite\sql\MysqlExactNumericLiteral.java"
Copy-Item $src $dest -Force
```

Add `MYCAT ADDITION` header with purpose: MySQL exact numeric literal handling.

- [ ] **Step 5: Copy RxBuiltInMethod.java from hbt**

```powershell
$src = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\util\RxBuiltInMethod.java"
$dest = "D:\Workspaces\IdeaProjects\Mycat2\calcite-core\src\main\java\org\apache\calcite\util\RxBuiltInMethod.java"
Copy-Item $src $dest -Force
```

Add `MYCAT ADDITION` header with purpose: RxJava built-in method references.

- [ ] **Step 6: Verify no io.mycat imports in any new file**

```powershell
$newFiles = @(
    "calcite-core\src\main\java\org\apache\calcite\mycat\MycatBuiltInMethod.java",
    "calcite-core\src\main\java\org\apache\calcite\mycat\MycatSqlDefinedFunction.java",
    "calcite-core\src\main\java\org\apache\calcite\sql\SqlForUpdate.java",
    "calcite-core\src\main\java\org\apache\calcite\sql\MysqlExactNumericLiteral.java",
    "calcite-core\src\main\java\org\apache\calcite\util\RxBuiltInMethod.java"
)
foreach ($f in $newFiles) {
    $content = Get-Content "D:\Workspaces\IdeaProjects\Mycat2\$f" -Raw
    if ($content -match "import\s+io\.mycat") {
        Write-Host "VIOLATION: $f contains io.mycat import!"
    } else {
        Write-Host "OK: $f (no io.mycat imports)"
    }
}
```

- [ ] **Step 7: Compile and verify**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile -pl calcite-core
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add calcite-core/src/main/java/org/apache/calcite/mycat/
git add calcite-core/src/main/java/org/apache/calcite/sql/SqlForUpdate.java
git add calcite-core/src/main/java/org/apache/calcite/sql/MysqlExactNumericLiteral.java
git add calcite-core/src/main/java/org/apache/calcite/util/RxBuiltInMethod.java
git commit -m "feat: add Mycat new files to calcite-core fork (5 classes)"
```

---

### Task 8: Switch hbt Dependency and Remove Fork Files

**Files:**
- Modify: `hbt/pom.xml`:262-265 (calcite-core dependency)
- Delete: All 68 files under `hbt/src/main/java/org/apache/calcite/`
- Create: 16 bridge files re-added to `hbt/src/main/java/org/apache/calcite/`

**Interfaces:**
- Consumes: Tasks 1-7 (calcite-core module complete and compiling)
- Produces: hbt depends on `io.mycat:calcite-core:1.42.0-mycat`, fork files removed, bridge files retained

- [ ] **Step 1: Switch hbt pom.xml dependency**

In `hbt/pom.xml`, replace:
```xml
        <!-- https://mvnrepository.com/artifact/org.apache.calcite/calcite-core -->
        <dependency>
            <groupId>org.apache.calcite</groupId>
            <artifactId>calcite-core</artifactId>
        </dependency>
```

With:
```xml
        <!-- Mycat Calcite fork (replaces org.apache.calcite:calcite-core) -->
        <dependency>
            <groupId>io.mycat</groupId>
            <artifactId>calcite-core</artifactId>
        </dependency>
```

- [ ] **Step 2: Delete all 68 fork files from hbt**

```powershell
$base = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite"
Get-ChildItem -Path $base -Recurse -Filter "*.java" | Remove-Item -Force
Write-Host "Deleted all fork files from hbt"
```

- [ ] **Step 3: Re-add 16 bridge files (with io.mycat imports)**

These files must stay in hbt because they import `io.mycat.*`:

```
adapter/enumerable/RexToLixTranslator.java
DataContext.java
MycatContext.java
mycat/MycatBuiltInMethodImpl.java
rel/externalize/RelJson.java
rel/externalize/RelJsonWriter.java
rel/metadata/RelMdCollation.java
rel/metadata/RelMdMaxRowCount.java
rel/metadata/RelMdRowCount.java
rel/metadata/RelMdUniqueKeys.java
rel/metadata/RelMdUtil.java
rel/rules/MycatHepJoinClustering.java
rex/RexLiteral.java
runtime/NewMycatDataContext.java
sql/validate/SqlValidatorImpl.java
util/RxBuiltInMethodImpl.java
```

Copy each from git history (before deletion) back to hbt:
```powershell
$bridgeFiles = @(
    "adapter/enumerable/RexToLixTranslator.java",
    "DataContext.java",
    "MycatContext.java",
    "mycat/MycatBuiltInMethodImpl.java",
    "rel/externalize/RelJson.java",
    "rel/externalize/RelJsonWriter.java",
    "rel/metadata/RelMdCollation.java",
    "rel/metadata/RelMdMaxRowCount.java",
    "rel/metadata/RelMdRowCount.java",
    "rel/metadata/RelMdUniqueKeys.java",
    "rel/metadata/RelMdUtil.java",
    "rel/rules/MycatHepJoinClustering.java",
    "rex/RexLiteral.java",
    "runtime/NewMycatDataContext.java",
    "sql/validate/SqlValidatorImpl.java",
    "util/RxBuiltInMethodImpl.java"
)
$gitBase = "hbt/src/main/java/org/apache/calcite"
foreach ($f in $bridgeFiles) {
    $gitPath = "$gitBase/$($f -replace '/', '/')"
    $destPath = "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\$($f -replace '/', '\')"
    $destDir = Split-Path $destPath -Parent
    if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
    # Restore from git (before our deletion in step 2)
    git show "HEAD:$gitPath" | Out-File -FilePath $destPath -Encoding UTF8
    Write-Host "Restored bridge: $f"
}
```

**IMPORTANT**: Run step 3 immediately after step 2, before any commit, so git can restore from HEAD.

- [ ] **Step 4: Verify bridge file count**

```powershell
$count = (Get-ChildItem -Path "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite" -Recurse -Filter "*.java" | Measure-Object).Count
Write-Host "Bridge files in hbt: $count"
if ($count -eq 16) { Write-Host "OK: matches expected 16 bridge files" } else { Write-Host "WARNING: expected 16, got $count" }
```

- [ ] **Step 5: Verify all bridge files have io.mycat imports**

```powershell
Get-ChildItem -Path "D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite" -Recurse -Filter "*.java" | ForEach-Object {
    $content = Get-Content $_.FullName -Raw
    $hasImport = $content -match "import\s+io\.mycat"
    $rel = $_.FullName.Substring("D:\Workspaces\IdeaProjects\Mycat2\hbt\src\main\java\org\apache\calcite\".Length)
    if ($hasImport) { Write-Host "OK: $rel" } else { Write-Host "WARNING: $rel has no io.mycat import" }
}
```

- [ ] **Step 6: Commit**

```bash
git add hbt/pom.xml
git add hbt/src/main/java/org/apache/calcite/
git commit -m "refactor: switch hbt to io.mycat:calcite-core fork, remove 52 non-bridge fork files"
```

---

### Task 9: Fix hbt Bridge File Compilation

**Files:**
- Potentially modify: All 16 bridge files in `hbt/src/main/java/org/apache/calcite/`
- Potentially modify: `hbt/src/main/java/io/mycat/` files that reference changed Calcite APIs

**Interfaces:**
- Consumes: Task 8 (dependency switched, fork files removed)
- Produces: hbt compiles cleanly with calcite-core fork

- [ ] **Step 1: Attempt hbt compilation**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile -pl hbt -am 2>&1 | Tee-Object -Variable compileOutput
```

- [ ] **Step 2: Categorize compilation errors**

Common error categories:
1. **API changes between Calcite 1.26→1.42**: Bridge files reference old 1.26 APIs that changed in 1.42
2. **Missing classes**: Non-bridge fork files that provided classes/methods used by hbt code (these should use 1.42.0 original from fork)
3. **Signature mismatches**: Bridge overrides have old method signatures
4. **Package-private access**: Code in hbt that accessed package-private members of non-bridge fork files

- [ ] **Step 3: Fix bridge files for Calcite 1.42.0 API compatibility**

For each bridge file with errors:
1. Read the Calcite 1.42.0 version of the same class (from `calcite-core/src/main/java/`)
2. Update the bridge file's method signatures, imports, and API calls to match 1.42.0
3. Preserve the `io.mycat` integration logic

- [ ] **Step 4: Fix hbt business code for API changes**

For hbt code under `io/mycat/` that referenced old fork file APIs:
1. Update imports to use Calcite 1.42.0 APIs
2. If a method was removed/renamed in 1.42, find the equivalent
3. If no equivalent exists, create a wrapper in hbt

- [ ] **Step 5: Iterative compile-fix cycle**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile -pl hbt -am
```

Repeat until `BUILD SUCCESS`. Commit after each significant batch of fixes:

```bash
git add hbt/
git commit -m "fix: adapt hbt bridge files and business code to Calcite 1.42.0 APIs"
```

- [ ] **Step 6: Final hbt compilation verification**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile -pl hbt -am
```
Expected: `BUILD SUCCESS`.

---

### Task 10: Full Project Compilation Verification

**Files:**
- Any files across the project that need final adjustments

**Interfaces:**
- Consumes: Tasks 1-9 (calcite-core fork complete, hbt rewired)
- Produces: Full project compiles cleanly

- [ ] **Step 1: Full project compile**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile 2>&1 | Tee-Object -Variable fullOutput
```

- [ ] **Step 2: Fix cross-module issues**

Other modules (mycat2, executor, etc.) may reference Calcite APIs that changed. Fix iteratively.

- [ ] **Step 3: Final full compile**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn compile
```
Expected: `BUILD SUCCESS` for entire project.

- [ ] **Step 4: Run hbt tests**

```powershell
cd D:\Workspaces\IdeaProjects\Mycat2
mvn test -pl hbt
```

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat: complete calcite-core fork migration — full project compiles cleanly"
```

---

## Verification Checklist

After all tasks complete, verify:

1. **calcite-core module exists**: `calcite-core/pom.xml` with `io.mycat:calcite-core:1.42.0-mycat`
2. **calcite-core compiles**: `mvn compile -pl calcite-core` → SUCCESS
3. **1646 + 5 = 1651 Java files** in `calcite-core/src/main/java/`
4. **~13 files have MYCAT FORK NOTICE** headers
5. **5 files have MYCAT ADDITION** headers
6. **No `io.mycat` imports** in any file under `calcite-core/`
7. **hbt depends on `io.mycat:calcite-core:1.42.0-mycat`** (not `org.apache.calcite:calcite-core`)
8. **16 bridge files** remain in `hbt/src/main/java/org/apache/calcite/` (all with `io.mycat` imports)
9. **Full project compiles**: `mvn compile` → SUCCESS
10. **hbt tests pass**: `mvn test -pl hbt` → SUCCESS (or known pre-existing failures only)
