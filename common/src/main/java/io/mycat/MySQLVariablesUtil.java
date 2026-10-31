package io.mycat;

import com.alibaba.druid.sql.SQLUtils;
import io.mycat.beans.mycat.TransactionType;
import io.mycat.protocol.mysql.type.MySQLErrorCode;

import java.util.Objects;

public class MySQLVariablesUtil {
    public static void setVariable(MycatDataContext dataContext,
                                   String target,
                                   Object text) {
        String value = SQLUtils.normalize(Objects.toString(text));
        if (target.contains("autocommit")) {
            dataContext.setAutoCommit(toInt(value) == 1);
        } else if (target.toLowerCase().contains("sql_mode")) {
            // I-DEBT-54 D2b-A：session 域 SET sql_mode 存原始串（去引号），供回读与向后端 diff-replay。
            // global 域写会话之外，不得误捕获进会话态。
            if (!target.toLowerCase().contains("global")) {
                String mode = value;
                // SQLUtils.normalize 对空引号串 '' 不剥离，显式剥一对首尾单引号，保证存的是纯模式列表。
                if (mode.length() >= 2 && mode.charAt(0) == '\'' && mode.charAt(mode.length() - 1) == '\'') {
                    mode = mode.substring(1, mode.length() - 1);
                }
                dataContext.setSessionSqlMode(mode);
            }
        } else if (target.toLowerCase().contains("max_error_count")) {
            // I-DEBT-69 Blade C：会话级覆盖值。真库边界（2026-10-31 活体 mysql 8.4.11 / mariadb 11.4.13 一致）：
            // 65536 → 65535、-1 → 0（clamp 到 [0,65535]，不报错）；'abc' → ERROR 1232。
            String raw = value;
            if (raw.length() >= 2 && raw.charAt(0) == '\'' && raw.charAt(raw.length() - 1) == '\'') {
                raw = raw.substring(1, raw.length() - 1);
            }
            if ("default".equalsIgnoreCase(raw.trim())) {
                dataContext.setSessionMaxErrorCount(null);
            } else {
                int parsed;
                try {
                    parsed = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    // 真库 8.4.11 实拍：SET max_error_count='abc' → ERROR 1232（ER_WRONG_TYPE_FOR_VAR），
                    // 旧行为是 1105 + Java 异常文本，客户端按 errorCode 分支会判错。
                    throw new MycatException(MySQLErrorCode.ER_WRONG_TYPE_FOR_VAR,
                            "Incorrect argument type to variable 'max_error_count'");
                }
                dataContext.setSessionMaxErrorCount(Math.max(0, Math.min(65535, parsed)));
            }
        } else if (target.equalsIgnoreCase("transaction_policy")) {
            if ("proxy".equalsIgnoreCase(value)) {
                dataContext.switchTransaction(TransactionType.PROXY_TRANSACTION_TYPE);
            } else if ("xa".equalsIgnoreCase(value)) {
                dataContext.switchTransaction(TransactionType.JDBC_TRANSACTION_TYPE);
            } else {
                throw new IllegalArgumentException("transaction_policy for " + value);
            }
        } else if (target.contains("net_write_timeout")) {
            dataContext.setVariable(MycatDataContextEnum.NET_WRITE_TIMEOUT, Long.parseLong(value));
        } else if ("SQL_SELECT_LIMIT".equalsIgnoreCase(target)) {
            if ("default".equalsIgnoreCase(value)) {
                value = Integer.toString(Integer.MAX_VALUE);
            }
            dataContext.setVariable(MycatDataContextEnum.SELECT_LIMIT, Long.parseLong(value));
        } else if ("character_set_results".equalsIgnoreCase(target)) {
            dataContext.setVariable(MycatDataContextEnum.CHARSET_SET_RESULT, value);
        } else if (target.contains("read_only")) {
            dataContext.setVariable(MycatDataContextEnum.IS_READ_ONLY, toInt(value));
        } else if (target.contains("transaction_isolation") || target.contains("tx_isolation")) {
            dataContext.setVariable(MycatDataContextEnum.IS_READ_ONLY, toInt(value));
        }
    }

    public static Object getVariable(MycatDataContext dataContext, String target) {
        target = target.toLowerCase();
        if (target.contains("autocommit")) {
            return dataContext.isAutocommit() ? 1 : 0;
        } else if (target.equalsIgnoreCase("transaction_policy")) {
            return dataContext.getTransactionSession().name();
        } else if (target.contains("net_write_timeout")) {
            return dataContext.getVariable(MycatDataContextEnum.NET_WRITE_TIMEOUT);
        } else if ("sql_select_limit".equalsIgnoreCase(target)) {
            return dataContext.getVariable(MycatDataContextEnum.SELECT_LIMIT);
        } else if ("character_set_results".equalsIgnoreCase(target)) {
            return dataContext.getVariable(MycatDataContextEnum.CHARSET_SET_RESULT);
        } else if (target.contains("read_only")) {
            return dataContext.getVariable(MycatDataContextEnum.IS_READ_ONLY);
        } else if (target.contains("current_user")) {
            return dataContext.getUser().getUserName();
        } else if (target.contains("transaction_isolation") || target.contains("tx_isolation")) {
            dataContext.getVariable(MycatDataContextEnum.ISOLATION);
        }
        //todo
//        Map<String, Object> map = RootHelper.INSTANCE.getConfigProvider().globalVariables();
//        MySQLVariablesEnum mySQLVariablesEnum = MySQLVariablesEnum.parseFromColumnName(target);
//        if (mySQLVariablesEnum != null) {
//            String columnName = mySQLVariablesEnum.getColumnName();
//            return map.getOrDefault(columnName, null);
//        } else {
//            return null;
//        }
        return null;
    }

    public static int toInt(Object s) {
        return toInt(Objects.toString(s));
    }

    public static int toInt(String s) {
        s = s.trim();
        if ("1".equalsIgnoreCase(s)) {
            return 1;
        }
        if ("0".equalsIgnoreCase(s)) {
            return 0;
        }
        if ("on".equalsIgnoreCase(s)) {
            return 1;
        }
        if ("off".equalsIgnoreCase(s)) {
            return 0;
        }
        if ("true".equalsIgnoreCase(s)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(s)) {
            return 0;
        }
        return Integer.parseInt(s);
    }
}