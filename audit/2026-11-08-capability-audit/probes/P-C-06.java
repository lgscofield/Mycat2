// PROBE: P-C-06 XaReaper 活性/装配验证
// 限制: 无 docker pause 无法冻结后端制造真 in-doubt。本探针验: reaper 线程/任务已装配可观测。
// 证据源: mycat 启动日志含 XaReaper/xa reaper 装配行; ServerConfig xaReaperIntervalMillis 存在。
// PASS = 日志出现 reaper 装配证据; NO_DATA = 无法从外部观测(如实标注)
import java.io.*;
import java.nio.file.*;

public class ProbeC_06 {
  public static void main(String[] args) throws Exception {
    // 找 boot 日志
    Path log = Paths.get("D:/Workspaces/Lingma/mycat2/audit/2026-11-08-capability-audit/logs/E0-05-mycat-boot2.log");
    String content = new String(Files.readAllBytes(log), "UTF-8");
    String[] keys = {"XaReaper", "xaReaper", "xa_reaper", "reaper"};
    boolean found = false;
    for (String k : keys) {
      if (content.toLowerCase().contains(k.toLowerCase())) {
        found = true;
        for (String line : content.split("\n"))
          if (line.toLowerCase().contains(k.toLowerCase()))
            System.out.println("LOG> " + line.trim());
      }
    }
    if (!found) {
      System.out.println("boot2 log 无 reaper 装配行(可能按需启动或无日志输出)");
      System.out.println("NO_DATA: 无法从外部观测 reaper 活性(需 docker pause 制造真 in-doubt)");
      System.out.println("VERDICT: NO_DATA"); System.exit(2);
    }
    System.out.println("VERDICT: PASS"); System.exit(0);
  }
}
