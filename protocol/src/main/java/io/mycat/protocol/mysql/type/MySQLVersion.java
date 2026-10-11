/**
 * Copyright (C) <2021>  <mycat>
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.protocol.mysql.type;

/**
 * 版本信息常量
 *
 * @author wuzhihui
 *
 */
public class MySQLVersion {

    /** 协议版本 **/
    public static final byte PROTOCOL_VERSION = 10;

    // I-VE-1: 服务器版本串/能力位/插件/字符集已收敛到 config 模块的不可变 VersionProfile 画像，
    // 原可变静态量 SERVER_VERSION_STRING / SERVER_VERSION / setServerVersion 已移除（坐实 I-VE-3 消除启动期全局可变量）。
}
