# Calcite-Core Fork Module Design

## Overview

This document defines the design for extracting Calcite fork files from the `hbt` module into a dedicated `calcite-core` Maven submodule. The goal is clean separation of concerns, easier Calcite upgrades, and reduced maintenance burden.

**Goal:** Create a `calcite-core` fork module that wraps Calcite 1.42.0 with minimal Mycat-specific modifications, replacing the current classpath-override pattern in `hbt`.

## Context

### Current Problem

Mycat2 currently uses a **classpath override** pattern: ~68 fork files are placed directly in `hbt/src/main/java/org/apache/calcite/`, overriding classes from the `calcite-core:1.42.0` Maven dependency. This causes:

- **Upgrade difficulty**: No clear diff between Mycat changes and Calcite version changes
- **Hidden dependencies**: 16 fork files silently import `io.mycat.*` classes, creating circular coupling
- **Annotation issues**: `@CalciteImmutable` package-level annotation doesn't apply to fork files
- **Maintenance opacity**: Impossible to tell which changes are Mycat customizations vs version drift

### Analysis Results

Of the 68 fork files in `hbt/src/main/java/org/apache/calcite/`:

| Category | Count | Description |
|----------|-------|-------------|
| PURE_OVERRIDE | 44 | Same FQCN as Calcite 1.42.0, no `io.mycat` imports |
| BRIDGE_OVERRIDE | 11 | Same FQCN as Calcite 1.42.0, imports `io.mycat` |
| NEW_PURE | 8 | New classes (not in Calcite), no `io.mycat` imports |
| NEW_BRIDGE | 5 | New classes, imports `io.mycat` |

**Critical finding**: Of the 44 PURE_OVERRIDE files, only **~10 have real Mycat customizations**. The remaining ~34 are Calcite 1.26.0 → 1.42.0 version drift with zero Mycat-specific logic.

## Design Decisions

### 1. Bridge-First Minimization

**Principle**: Minimize modifications to the calcite-core fork. Push Mycat-specific behavior to the `hbt` bridge layer via Calcite's extension points (SPI, pluggable interfaces, custom implementations).

**Consequence**: The fork contains only ~18 modified files (10 customizations + 8 additions) instead of 52. The remaining ~34 version-drift files use Calcite 1.42.0 source unchanged.

### 2. Module Structure

```
mycat2/
  calcite-core/                          # New module
    pom.xml                              # io.mycat:calcite-core:1.42.0-mycat
    src/main/java/
      org/apache/calcite/
        package-info.java                # Contains @CalciteImmutable
        ... (1646 files from Calcite 1.42.0 sources JAR)
        ... (~10 files with Mycat modifications overlaid)
        adapter/enumerable/
          MycatBuiltInMethod.java        # NEW: Mycat type conversion enums
          MycatSqlDefinedFunction.java   # NEW: Mycat custom function base
        sql/
          SqlForUpdate.java              # NEW: FOR UPDATE support
          MysqlExactNumericLiteral.java  # NEW: MySQL numeric literal
        util/
          RxBuiltInMethod.java           # NEW: RxJava method refs
        linq4j/
          EnumerableDefaults.java        # NEW: Extended defaults
  hbt/
    pom.xml                              # Depends on io.mycat:calcite-core:1.42.0-mycat
    src/main/java/
      org/apache/calcite/mycat/          # 3 bridge files (MycatBuiltInMethodImpl, etc.)
      org/apache/calcite/...             # 13 bridge files with io.mycat imports
      io/mycat/...                       # Mycat business code
```

### 3. Maven Coordinates

| Module | GroupId | ArtifactId | Version |
|--------|---------|------------|---------|
| calcite-core fork | `io.mycat` | `calcite-core` | `1.42.0-mycat` |
| calcite-linq4j | `org.apache.calcite` | `calcite-linq4j` | `1.42.0` (unchanged) |
| avatica-core | `org.apache.calcite.avatica` | `avatica-core` | `1.26.0` (unchanged) |

### 4. Dependency Wiring

**calcite-core fork pom.xml**:
- Parent: `io.mycat:mycat-parent`
- Dependencies: same as Calcite 1.42.0 (`calcite-linq4j`, `avatica-core`, `guava`, `janino`, `jackson`, `jts-core`, etc.)
- Annotation processors: `org.immutables:value:2.10.1`
- **No `io.mycat` dependencies** (enforced by Maven Enforcer)

**hbt pom.xml change**:
```xml
<!-- Remove -->
<dependency>
  <groupId>org.apache.calcite</groupId>
  <artifactId>calcite-core</artifactId>
</dependency>

<!-- Add -->
<dependency>
  <groupId>io.mycat</groupId>
  <artifactId>calcite-core</artifactId>
  <version>1.42.0-mycat</version>
</dependency>
```

`calcite-linq4j` and `avatica-core` dependencies in hbt remain unchanged (transitive from fork).

### 5. Comment Convention

**File-level header** (every Mycat-modified file):
```java
/*
 * MYCAT FORK NOTICE
 * Base: Calcite 1.42.0 (org.apache.calcite:calcite-core:1.42.0)
 * Purpose: [one-line reason for forking this file]
 * Changes:
 *   - [change summary 1]
 *   - [change summary 2]
 * Last reviewed against Calcite version: 1.42.0
 */
```

**Inline markers** (each modification point):
```java
// MYCAT CHANGE: [why this was changed]
// Original (Calcite 1.42.0): [original code or description]
```

**New file header** (NEW_PURE files):
```java
/*
 * MYCAT ADDITION
 * This class does not exist in Calcite 1.42.0.
 * Purpose: [why this class exists]
 * Reason for org.apache.calcite package: [why it must be here, not in io.mycat]
 *   (typically: access to package-private members)
 */
```

## Files Classification

### Files in calcite-core fork (with Mycat modifications)

| File | Modification Type | Purpose |
|------|-------------------|---------|
| `adapter/enumerable/RexImpTable.java` | Customize | Register Mycat custom function implementations |
| `sql/validate/implicit/AbstractTypeCoercion.java` | Customize | MySQL type coercion rules |
| `sql/fun/SqlCastFunction.java` | Customize | MySQL CAST behavior |
| `sql/SqlLiteral.java` | Customize | Mycat literal extensions |
| `config/CalciteSystemProperty.java` | Customize | MySQL-related config properties |
| `prepare/CalciteCatalogReader.java` | Customize | Custom catalog reading |
| `rel/rules/JoinToMultiJoinRule.java` | Customize | Custom JOIN clustering |
| `adapter/enumerable/EnumerableRelImplementor.java` | Customize | Mycat execution extensions |
| `adapter/enumerable/PhysTypeImpl.java` | Customize | Mycat physical type support |
| `adapter/enumerable/EnumUtils.java` | Customize | Mycat enumerable utilities |
| `runtime/Bindable.java` | Extend | Extended bindable interface for Mycat runtime |
| `linq4j/EnumerableDefaults.java` | Extend | Extended linq4j default methods |
| `avatica/util/TimeUnitRange.java` | Extend | Time unit range extension |

### New files in calcite-core fork (Mycat additions, not in Calcite 1.42.0)

| File | Purpose |
|------|----------|
| `mycat/MycatBuiltInMethod.java` | Mycat type conversion method enums |
| `mycat/MycatSqlDefinedFunction.java` | Mycat custom SQL function base class |
| `sql/SqlForUpdate.java` | FOR UPDATE clause support |
| `sql/MysqlExactNumericLiteral.java` | MySQL exact numeric literal handling |
| `util/RxBuiltInMethod.java` | RxJava built-in method references |

### Bridge files (stay in hbt)

These 16 files import `io.mycat.*` and remain in hbt:

| File | io.mycat Dependencies |
|------|----------------------|
| `DataContext.java` | `MycatDataContext` |
| `MycatContext.java` | `Authenticator, MetaClusterCurrent, MycatDataContext, MycatUser` |
| `runtime/NewMycatDataContext.java` | `DrdsSqlWithParams, MetaClusterCurrent, MycatDataContext` |
| `mycat/MycatBuiltInMethodImpl.java` | `MycatTimeUtil` |
| `adapter/enumerable/RexToLixTranslator.java` | `StrictEqualFunction` |
| `sql/validate/SqlValidatorImpl.java` | `MycatCalciteSupport, StrictEqualFunction` |
| `rel/externalize/RelJson.java` | `SchemaInfo, MycatCalciteSupport` |
| `rel/externalize/RelJsonWriter.java` | `MycatView` |
| `rel/metadata/RelMdCollation.java` | `MycatView` |
| `rel/metadata/RelMdMaxRowCount.java` | `ParamHolder` |
| `rel/metadata/RelMdRowCount.java` | `ParamHolder` |
| `rel/metadata/RelMdUniqueKeys.java` | `MycatView` |
| `rel/metadata/RelMdUtil.java` | `MycatCalciteSupport, ParamHolder` |
| `rel/rules/MycatHepJoinClustering.java` | `MycatCalciteSupport, MycatView, Distribution` |
| `rex/RexLiteral.java` | `MycatRexExecutor, ParamHolder` |
| `util/RxBuiltInMethodImpl.java` | `MycatRxJavaUtl, MaterializedRecordSetFactory` |

### Version-drift files (NOT in fork, use Calcite 1.42.0 original)

~34 files that were forked from Calcite 1.26.0 but contain no Mycat-specific logic. These will use the clean Calcite 1.42.0 source. Examples:

- `sql/SqlUtil.java`, `sql/SqlTypeName.java`, `sql/SqlOperatorTable.java`
- `rel/type/RelDataTypeFactoryImpl.java`, `sql/type/SqlTypeUtil.java`
- `tools/Frameworks.java`, `rex/RexBuilder.java`, `rex/RexExecutable.java`
- `plan/RelOptCostImpl.java`, `plan/volcano/VolcanoCost.java`
- `sql2rel/StandardConvertletTable.java`

## Implementation Phases

### Phase 0-A: Create calcite-core skeleton

1. Create `calcite-core/` directory and `pom.xml` (version `1.42.0-mycat`)
2. Extract all 1646 source files from `calcite-core-1.42.0-sources.jar` into `calcite-core/src/main/java/`
3. Configure Immutables annotation processor in `calcite-core/pom.xml`
4. Verify: `mvn compile -pl calcite-core` passes with clean Calcite source

### Phase 0-B: Apply Mycat customizations

For each of the ~10 files with real Mycat modifications:
1. Start from the Calcite 1.42.0 baseline (already extracted)
2. Compare with hbt's old fork file to extract Mycat-specific changes
3. Apply changes with `MYCAT CHANGE` comment annotations
4. Add the 8 NEW_PURE files with `MYCAT ADDITION` headers
5. Verify: `mvn compile -pl calcite-core` after each batch

### Phase 0-C: hbt bridge layer refactor

1. Delete all 68 fork files from `hbt/src/main/java/org/apache/calcite/`
2. Re-add the 16 BRIDGE files (with `io.mycat` imports) to hbt
3. Update hbt `pom.xml`: switch to `io.mycat:calcite-core:1.42.0-mycat`
4. For the ~34 version-drift files: adapt hbt code to use Calcite 1.42.0 APIs via:
   - Calcite extension points (pluggable factories, custom implementations)
   - Wrapper/subclass patterns in hbt
   - Only fork source files when no extension point exists
5. Verify: `mvn compile -pl hbt` — fix errors iteratively

### Phase 0-D: Full compilation verification

1. `mvn compile` full project
2. Fix any remaining cross-module issues
3. Commit

## Future Upgrade Path

When upgrading Calcite (e.g., 1.42.0 → 1.50.0):

1. Replace calcite-core source baseline with 1.50.0
2. Re-apply Mycat modifications using `MYCAT CHANGE` markers as a guide
3. Resolve API conflicts
4. `mvn compile` to verify
5. Update `Last reviewed against Calcite version` in file headers

## Risks

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Calcite 1.42.0 annotation processor config incompatibility | Medium | High | Test clean compile in Phase 0-A before any modifications |
| Bridge files incompatible with Calcite 1.42.0 APIs | High | High | Fix iteratively in Phase 0-C; bridge files are in hbt for easy iteration |
| Mycat customizations depend on Calcite 1.26 internals removed in 1.42 | High | High | Deep analysis per file in Phase 0-B; use extension points where possible |
| Version-drift files needed by hbt with 1.26 API assumptions | Medium | Medium | hbt bridge layer adapts to 1.42 APIs; wrapper classes if needed |
| `@CalciteImmutable` / Immutables version mismatch | Low | Medium | Use same Immutables 2.10.1 as Calcite 1.42.0 |
