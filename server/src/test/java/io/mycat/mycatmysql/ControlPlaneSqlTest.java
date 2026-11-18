package io.mycat.mycatmysql;

import io.mycat.hint.KillThreadHint;
import io.mycat.hint.ShowThreadInfoHint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-PR-1 D2：控制面 SQL 豁免准入闸（Review Focus #5）。
 * 饱和时运维还得能发 hint 查线程 / 杀线程，否则「越忙越查不了」——
 * 这正是准入闸最容易把运维自己锁在门外的一处。
 *
 * <p>判定只做前缀，不引解析器：跳过前导空白与块注释后，
 * 大小写不敏感匹配 mycat hint 前缀或 show @@ 前缀。
 */
public class ControlPlaneSqlTest {

    @Test
    public void recognizesMycatHint() {
        assertTrue(ControlPlaneSql.isControlPlane("/*mycat:cmd=showThreadInfo*/"),
                "mycat hint 必须豁免");
        assertTrue(ControlPlaneSql.isControlPlane("/*mycat:cmd=killThread,id=123*/"),
                "killThread hint 必须豁免");
    }

    @Test
    public void recognizesShowAtAt() {
        assertTrue(ControlPlaneSql.isControlPlane("show @@thread"), "show @@ 管理语句必须豁免");
        assertTrue(ControlPlaneSql.isControlPlane("SHOW @@datasource"), "大小写不敏感");
        assertTrue(ControlPlaneSql.isControlPlane("show  @@thread"), "show 与 @@ 之间的多余空白不得漏判");
    }

    /**
     * 钉住仓库自己的 hint 生成器产物：HintBuilder.build() 发出的文本带加号标记、
     * cmd 后跟 JSON 体、注释后带尾分号。只认裸 mycat 冒号前缀的朴素判定
     * 会把运维实际发出的 hint 全漏掉。
     */
    @Test
    public void recognizesRepoEmittedHintForms() {
        assertTrue(ControlPlaneSql.isControlPlane(ShowThreadInfoHint.create()),
                "ShowThreadInfoHint 生成器产物必须豁免");
        assertTrue(ControlPlaneSql.isControlPlane(KillThreadHint.create(123)),
                "KillThreadHint 生成器产物必须豁免");
        assertTrue(ControlPlaneSql.isControlPlane("/*! mycat:cmd=showThreadInfo*/"),
                "/*! 标记变体（DropSchemaHint 等用）必须豁免");
    }

    /**
     * 反闸绕过：若「带 mycat hint 前缀」就豁免，任何客户端给普通 DML 前面挂一个假 hint
     * 即可永久逃过准入闸，背压形同虚设。HintHandler 只认 hint-only 语句，此处口径必须一致。
     */
    @Test
    public void hintPrefixedDmlIsNotExempt() {
        assertFalse(ControlPlaneSql.isControlPlane("/*mycat:cmd=showThreadInfo*/ select 1"),
                "hint 后跟 DML ⇒ 不是控制面语句，不得豁免（否则准入闸可被绕过）");
        assertFalse(ControlPlaneSql.isControlPlane("/*+ mycat:cmd=bogus*/ delete from t"),
                "假 hint + 真 DML 同样不得豁免");
    }

    /** 这条钉住朴素 startsWith 的脆弱点：真实客户端会带前导空白与无关注释。 */
    @Test
    public void toleratesLeadingWhitespaceAndComments() {
        assertTrue(ControlPlaneSql.isControlPlane("  \n/*x*/ /*mycat:cmd=showThreadInfo*/"),
                "前导空白 + 无关注释不得让 hint 漏判");
    }

    @Test
    public void doesNotExemptOrdinaryDml() {
        assertFalse(ControlPlaneSql.isControlPlane("select 1"), "普通查询必须经闸");
        assertFalse(ControlPlaneSql.isControlPlane("insert into t values(1)"), "普通写入必须经闸");
        assertFalse(ControlPlaneSql.isControlPlane("/*mycat*/ select 1"),
                "/*mycat*/ 不带冒号命令 ⇒ 不是 hint，不得豁免");
    }

    @Test
    public void nullAndEmptyAreNotControlPlane() {
        assertFalse(ControlPlaneSql.isControlPlane(null), "null 不得 NPE");
        assertFalse(ControlPlaneSql.isControlPlane(""), "空串不得豁免");
        assertFalse(ControlPlaneSql.isControlPlane("   \n\t "), "全空白不得豁免");
    }
}