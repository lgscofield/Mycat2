package io.mycat.sql;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.alibaba.druid.sql.MycatSQLUtils;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.visitor.SQLASTOutputVisitor;

/**
 * druid 影子类删除前后的 SQL 序列化黄金基线（spec 2026-09-30-druid-shadow-removal D6）。
 *
 * 语料逐条走 MycatSQLUtils.toString(parse(sql))，输出与 druid-output-baseline.txt 对账。
 * -Dgolden.update=true 时改为重写基线，并断言 visitor 实现来自 plug 影子（采集锚点）。
 *
 * 用 JUnit 5 而非本模块既有的 JUnit 4：仓库无 junit-vintage-engine，JUnit 4 测试
 * 实测 Tests run: 0（静默不跑），量不到的门不成门。见账本 Task 1 Ruling。
 */
public class DruidOutputGoldenTest {

	private static final int CORPUS_SIZE = 17;
	private static final File CORPUS = new File("src/test/resources/io/mycat/sql/golden-corpus.txt");
	private static final File BASELINE = new File("src/test/resources/io/mycat/sql/druid-output-baseline.txt");

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

	/** 输出可能含换行/制表，基线文件每条一行，故转义成字面量。 */
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

	private static final String ERROR_PREFIX = "!!ERR!! ";

	/**
	 * 影子在部分语料上本来就是坏的（实测：`SET ...` 抛出
	 * NoSuchMethodError: SQLExprParser.parseAssignItems(List, SQLObject, boolean)）。
	 * 错误必须入档而非抛出去，否则基线采不全，Task 4 也会错过这个"从错到对"的信号。
	 */
	private static String render(String sql) {
		try {
			SQLStatement statement = SQLUtils.parseSingleMysqlStatement(sql);
			return MycatSQLUtils.toString(statement);
		} catch (Throwable t) {
			return ERROR_PREFIX + t.getClass().getName() + ": " + t.getMessage();
		}
	}

	@Test
	public void goldenOutputMatchesBaseline() throws Exception {
		Assertions.assertTrue(CORPUS.exists(), "corpus missing: " + CORPUS.getAbsolutePath());
		List<String> corpus = readLines(CORPUS);
		Assertions.assertEquals(CORPUS_SIZE, corpus.size(), "语料条数必须与计划一致");

		if (Boolean.getBoolean("golden.update")) {
			String visitorFrom = SQLASTOutputVisitor.class.getProtectionDomain().getCodeSource().getLocation()
					.toString();
			System.out.println("visitor from " + visitorFrom);
			Assertions.assertTrue(visitorFrom.contains("plug") && !visitorFrom.contains("druid-1.2.28.jar"),
					"采集基线时必须由 plug 影子胜出，实际来源：" + visitorFrom);
			try (PrintWriter writer = new PrintWriter(
					new OutputStreamWriter(new FileOutputStream(BASELINE), StandardCharsets.UTF_8))) {
				for (int i = 0; i < corpus.size(); i++) {
					writer.println((i + 1) + "\t" + encode(render(corpus.get(i))));
				}
			}
			Assertions.assertEquals(CORPUS_SIZE, readLines(BASELINE).size());
			return;
		}

		Assertions.assertTrue(BASELINE.exists(),
				"基线尚未采集，先跑 -Dgolden.update=true：" + BASELINE.getAbsolutePath());
		List<String> baseline = readLines(BASELINE);
		Assertions.assertEquals(corpus.size(), baseline.size(), "基线条数与语料条数不符");
		for (int i = 0; i < corpus.size(); i++) {
			String expect = baseline.get(i);
			int tab = expect.indexOf('\t');
			Assertions.assertTrue(tab > 0, "基线缺少序号前缀 #" + (i + 1));
			Assertions.assertEquals(i + 1, Integer.parseInt(expect.substring(0, tab)), "基线序号错位 #" + (i + 1));
			String expected = decode(expect.substring(tab + 1));
			String sql = corpus.get(i);
			String actual = render(sql);
			if (!expected.equals(actual)) {
				Assertions.fail("golden diff #" + (i + 1) + ": " + sql + " expected=<" + encode(expected)
						+ "> actual=<" + encode(actual) + ">");
			}
		}
	}
}
