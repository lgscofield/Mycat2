/**
 * Copyright (C) <2026> <Mycat2>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.calcite;

import com.alibaba.druid.sql.ast.SQLStatement;
import io.mycat.DrdsSqlWithParams;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * I-DEBT-3 Task 2：Calcite 校验行为的黄金基线。
 *
 * <p>
 * 这里锁的是“Mycat 现在校验出什么类型”，不是 fork 的基线。副本（hbt 的 org.apache.calcite.sql.validate.SqlValidatorImpl）
 * 删除前后各采一次，逐条对比，差异必须分类裁定（真定制丢失 / 版本漂移 / 定制叠在旧版上）。
 *
 * <p>
 * 只走 validate + getValidatedNodeType，不走 convertQuery：副本在位时 fork 的 SqlToRelConverter.convertSelect 会对
 * getMeasureScope 的结果做无条件强转（calcite-core/src/main/java/org/apache/calcite/sql2rel/SqlToRelConverter.java:734-735），
 * 那条路属于 Task 6 的端到端范围，混进来只会让对账噪声盖住类型差异。
 */
public class CalciteValidatorGoldenTest {

	private static final int CORPUS_SIZE = 48;
	private static final String DEFAULT_SCHEMA = "mycat";
	private static final File CORPUS = new File("src/test/resources/io/mycat/calcite/calcite-validator-corpus.txt");
	private static final File BASELINE = new File("src/test/resources/io/mycat/calcite/calcite-validator-baseline.txt");
	private static final String ERROR_PREFIX = "!!ERR!! ";

	private static final CalciteCatalogReader CATALOG_READER =
			DrdsRunnerHelper.newCalciteCatalogReader(rootSchemaWithFixtureTables());

	/**
	 * fixture 的三张表，副本与 fork 两次采集共用，可空性天然对齐。
	 *
	 * <p>
	 * 表必须挂在名为 mycat 的**子 schema** 里（与生产同构：DrdsRunnerHelper.convertRoSchemaPlus 用
	 * plus.add(schemaName, schema)），因为 preParse(sql, "mycat") 与 calciteConnectionConfig 的默认 schema 是 mycat；
	 * 直接挂 root 会让 48 条里 43 条报 "Object 'mycat' not found"，基线就没信息量了。
	 */
	private static org.apache.calcite.schema.SchemaPlus rootSchemaWithFixtureTables() {
		org.apache.calcite.schema.SchemaPlus rootSchemaPlus = CalciteSchema.createRootSchema(false).plus();
		final Map<String, Table> tables = new HashMap<>();
		tables.put("t1", fixtureTable(t1RowType()));
		tables.put("t2", fixtureTable(t2RowType()));
		tables.put("employee", fixtureTable(employeeRowType()));
		rootSchemaPlus.add(DEFAULT_SCHEMA, new AbstractSchema() {
			@Override
			protected Map<String, Table> getTableMap() {
				return tables;
			}
		});
		return rootSchemaPlus;
	}

	/**
	 * t1(id INT NOT NULL, name VARCHAR(64), age INT, amount DECIMAL(10,2), c_date DATE, c_ts TIMESTAMP, flag BOOLEAN)
	 */
	private static RelDataType t1RowType() {
		RelDataTypeFactory.Builder builder = MycatCalciteSupport.TypeFactory.builder();
		builder.add("id", sqlType(SqlTypeName.INTEGER));
		builder.add("name", nullable(sqlType(SqlTypeName.VARCHAR, 64)));
		builder.add("age", nullable(sqlType(SqlTypeName.INTEGER)));
		builder.add("amount", nullable(sqlType(SqlTypeName.DECIMAL, 10, 2)));
		builder.add("c_date", nullable(sqlType(SqlTypeName.DATE)));
		builder.add("c_ts", nullable(sqlType(SqlTypeName.TIMESTAMP)));
		builder.add("flag", nullable(sqlType(SqlTypeName.BOOLEAN)));
		return builder.build();
	}

	/**
	 * t2(id INT NOT NULL, t1_id INT, note VARCHAR(64), amount DECIMAL(10,2))
	 */
	private static RelDataType t2RowType() {
		RelDataTypeFactory.Builder builder = MycatCalciteSupport.TypeFactory.builder();
		builder.add("id", sqlType(SqlTypeName.INTEGER));
		builder.add("t1_id", nullable(sqlType(SqlTypeName.INTEGER)));
		builder.add("note", nullable(sqlType(SqlTypeName.VARCHAR, 64)));
		builder.add("amount", nullable(sqlType(SqlTypeName.DECIMAL, 10, 2)));
		return builder.build();
	}

	/**
	 * employee(id INT NOT NULL, name VARCHAR(64), deptno INT, salary DECIMAL(10,2))
	 */
	private static RelDataType employeeRowType() {
		RelDataTypeFactory.Builder builder = MycatCalciteSupport.TypeFactory.builder();
		builder.add("id", sqlType(SqlTypeName.INTEGER));
		builder.add("name", nullable(sqlType(SqlTypeName.VARCHAR, 64)));
		builder.add("deptno", nullable(sqlType(SqlTypeName.INTEGER)));
		builder.add("salary", nullable(sqlType(SqlTypeName.DECIMAL, 10, 2)));
		return builder.build();
	}

	private static RelDataType sqlType(SqlTypeName typeName) {
		return MycatCalciteSupport.TypeFactory.createSqlType(typeName);
	}

	private static RelDataType sqlType(SqlTypeName typeName, int precision) {
		return MycatCalciteSupport.TypeFactory.createSqlType(typeName, precision);
	}

	private static RelDataType sqlType(SqlTypeName typeName, int precision, int scale) {
		return MycatCalciteSupport.TypeFactory.createSqlType(typeName, precision, scale);
	}

	/** fork 的 Builder 没有 addNullable，可空列靠 createTypeWithNullability 表达。 */
	private static RelDataType nullable(RelDataType type) {
		return MycatCalciteSupport.TypeFactory.createTypeWithNullability(type, true);
	}

	private static Table fixtureTable(final RelDataType rowType) {
		return new AbstractTable() {
			@Override
			public RelDataType getRowType(RelDataTypeFactory typeFactory) {
				return rowType;
			}
		};
	}

	private static List<String> readLines(File file) throws Exception {
		List<String> lines = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isEmpty()) {
					lines.add(line);
				}
			}
		}
		return lines;
	}

	/** 错误消息可能含换行/制表，基线文件每条一行，故转义成字面量。 */
	private static String encode(String raw) {
		return raw.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n");
	}

	private static String decode(String stored) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < stored.length(); i++) {
			char c = stored.charAt(i);
			if (c == '\\' && i + 1 < stored.length()) {
				char next = stored.charAt(++i);
				if (next == 'n') {
					sb.append('\n');
					continue;
				}
				if (next == 't') {
					sb.append('\t');
					continue;
				}
			}
			sb.append(c);
		}
		return sb.toString();
	}

	private static String renderFields(RelDataType rowType) {
		if (rowType == null) {
			return "<null>";
		}
		StringBuilder sb = new StringBuilder();
		for (RelDataTypeField field : rowType.getFieldList()) {
			if (sb.length() > 0) {
				sb.append(",");
			}
			sb.append(field.getName()).append("=").append(field.getValue().getFullTypeString());
		}
		return sb.toString();
	}

	/**
	 * 校验错误同样入档而非抛出：副本时代 Mycat 对某些语料本就是报错的，Task 5 需要看到“从报错到通过”和“消息文本漂移”这两类变化。
	 */
	private static String render(String sql) {
		try {
			DrdsSqlWithParams drdsSql = DrdsRunnerHelper.preParse(sql, DEFAULT_SCHEMA);
			SqlValidator validator = DrdsRunnerHelper.getSqlValidator(drdsSql, CATALOG_READER);
			SQLStatement stmt = drdsSql.getParameterizedStatement();
			MycatCalciteMySqlNodeVisitor visitor = new MycatCalciteMySqlNodeVisitor();
			stmt.accept(visitor);
			SqlNode validated = validator.validate(visitor.getSqlNode());
			return "cols=" + renderFields(validator.getValidatedNodeType(validated));
		} catch (Throwable t) {
			return ERROR_PREFIX + t.getClass().getName() + ": " + t.getMessage();
		}
	}

	@Test
	public void goldenValidationMatchesBaseline() throws Exception {
		Assertions.assertTrue(CORPUS.exists(), "corpus missing: " + CORPUS.getAbsolutePath());
		List<String> corpus = readLines(CORPUS);
		Assertions.assertEquals(CORPUS_SIZE, corpus.size(), "语料条数必须与计划一致");

		// 来源锚点无条件校验：对账时悄悄换了实现（副本已删）也必须被发现，否则“全绿”是假绿。
		String from = System.getProperty("golden.validator", "copy");
		String realFrom = SqlValidatorImpl.class.getProtectionDomain().getCodeSource().getLocation().toString();
		System.out.println("validator from " + realFrom);
		if ("copy".equals(from)) {
			Assertions.assertTrue(realFrom.contains("hbt") && !realFrom.contains("calcite-core"),
					"对账要求由 hbt 副本胜出，实际来源：" + realFrom);
		} else {
			Assertions.assertTrue(realFrom.contains("calcite-core"),
					"对账要求由 calcite-core fork 胜出，实际来源：" + realFrom);
		}

		if (Boolean.getBoolean("golden.update")) {
			try (BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(new FileOutputStream(BASELINE), StandardCharsets.UTF_8))) {
				for (int i = 0; i < corpus.size(); i++) {
					writer.write((i + 1) + "\t" + encode(render(corpus.get(i))));
					writer.newLine();
				}
			}
			Assertions.assertEquals(CORPUS_SIZE, readLines(BASELINE).size(), "基线条数应与语料一致");
			return;
		}

		Assertions.assertTrue(BASELINE.exists(),
				"基线尚未采集，先跑 -Dgolden.update=true：" + BASELINE.getAbsolutePath());
		List<String> baseline = readLines(BASELINE);
		Assertions.assertEquals(corpus.size(), baseline.size(), "基线条数与语料条数不符");
		List<String> diffs = new ArrayList<>();
		for (int i = 0; i < corpus.size(); i++) {
			String expect = baseline.get(i);
			String actual = (i + 1) + "\t" + encode(render(corpus.get(i)));
			if (!expect.equals(actual)) {
				diffs.add("golden diff #" + (i + 1) + " sql=" + corpus.get(i)
						+ "\n  expect: " + decode(expect.substring(expect.indexOf('\t') + 1))
						+ "\n  actual: " + decode(actual.substring(actual.indexOf('\t') + 1)));
			}
		}
		if (!diffs.isEmpty()) {
			Assertions.fail("校验行为与基线不一致（" + diffs.size() + " 条）：\n" + String.join("\n", diffs));
		}
	}
}
