/*
 * I-DEBT-125 离线锁：loaddata 任意文件读封堵。
 *
 * 活体点位（修复前）：HintHandler "loaddata" 分支对 body.fileName 直接 new FileReader(fileName)，
 * 任意绝对路径（/etc/passwd、C:\secret）均可被服务端读出并 INSERT 进目标表——任意文件读原语。
 * 本锁钉两件事：
 *  1) 门：loaddata 属管理面 cmd，isManagementHint 必须命中（非 admin 在 :158 统一拒）；
 *  2) 路径白名单（纯函数，不必真执行 loaddata）：绝对路径与 ".." 穿越一概拒绝（admin 会话同样拒），
 *     仅放行无穿越的相对路径。
 */
package io.mycat.sqlhandler.dql;

import io.mycat.MycatException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HintHandlerLoaddataGateTest {

    @Test
    public void loaddataIsManagementHint() {
        assertTrue(HintHandler.isManagementHint("loaddata"),
                "loaddata 触碰服务端文件系统，必须过 admin 门");
    }

    @Test
    public void absolutePathsAreRejected() {
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("/etc/passwd"));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("/d/work/secret.csv"));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("\\Windows\\system.ini"));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("C:\\secret.txt"));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("c:/users/x.csv"));
    }

    @Test
    public void traversalPathsAreRejected() {
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("../secret.csv"));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("data/../../etc/passwd"));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("a/../b.csv"));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("..\\secret.csv"));
    }

    @Test
    public void nullAndEmptyAreRejected() {
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath(null));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath(""));
        assertThrows(MycatException.class, () -> HintHandler.checkLoaddataFilePath("   "));
    }

    @Test
    public void plainRelativePathsAreAllowed() {
        assertDoesNotThrow(() -> HintHandler.checkLoaddataFilePath("data.csv"));
        assertDoesNotThrow(() -> HintHandler.checkLoaddataFilePath("import/users.csv"));
        assertDoesNotThrow(() -> HintHandler.checkLoaddataFilePath("data\\users.csv"));
    }

    @Test
    public void rejectionCarriesTicketMark() {
        try {
            HintHandler.checkLoaddataFilePath("/etc/passwd");
        } catch (MycatException e) {
            assertTrue(String.valueOf(e.getMessage()).contains("I-DEBT-125"),
                    "拒绝消息需带票号，便于线上定位策略来源");
        }
    }
}
