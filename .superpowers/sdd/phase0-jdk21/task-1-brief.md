# Task 1 Brief: 升级 maven-compiler-plugin 并设置 JDK 21 target

**From plan:** `docs/superpowers/plans/2026-09-28-phase0-jdk21-compilation.md`

## Global Constraints

- JDK target: 21 (`<release>21</release>`)
- 每个任务完成后 `mvn compile -DskipTests` 必须通过
- 每个任务一个 git commit
- 禁止使用 `sun.*` 或 `com.sun.*` 内部 API（JDK 21 强封装）
- 依赖版本由根 `pom.xml` 的 `<dependencyManagement>` 集中管理
- PowerShell 中用 `;` 而非 `&&` 连接命令

## Task Description

### Task 1: 升级 maven-compiler-plugin 并设置 JDK 21 target

**Files:**
- Modify: `pom.xml`（根 POM 的 `<build><plugins>` 部分）

**Steps:**

1. 定位当前 compiler plugin 配置：在 `pom.xml` 中搜索 `maven-compiler-plugin`
2. 替换为 JDK 21 配置：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.14.1</version>
    <configuration>
        <release>21</release>
        <encoding>UTF-8</encoding>
    </configuration>
</plugin>
```

注意：`<release>21</release>` 替代了 `<source>` + `<target>` 的组合。

3. 验证：`mvn help:effective-pom -pl config` 输出包含 release=21
4. Commit: `git commit -m "build: upgrade maven-compiler-plugin to 3.14.1 with JDK 21 target"`

## Verification

- `mvn help:effective-pom -pl config` 输出包含 `<release>21</release>`
- 一个 git commit
