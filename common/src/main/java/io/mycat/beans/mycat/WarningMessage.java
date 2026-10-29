package io.mycat.beans.mycat;

import java.util.Objects;

/**
 * 一条诊断条件（MySQL {@code SHOW WARNINGS} 的一行：Level / Code / Message）。
 *
 * <p>字段与真库结果集列一一对应（活体实证列名为 {@code Level, Code, Message}），
 * 不可增删，否则与客户端眼中 MySQL 的 SHOW WARNINGS 结果集结构不一致。
 */
public final class WarningMessage {
    private final String level;
    private final int code;
    private final String message;

    public WarningMessage(String level, int code, String message) {
        this.level = Objects.requireNonNull(level, "level");
        this.code = code;
        this.message = Objects.requireNonNull(message, "message");
    }

    public String getLevel() {
        return level;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WarningMessage)) {
            return false;
        }
        WarningMessage other = (WarningMessage) o;
        return code == other.code
                && Objects.equals(level, other.level)
                && Objects.equals(message, other.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(level, code, message);
    }

    @Override
    public String toString() {
        return level + " | " + code + " | " + message;
    }
}
