package io.mycat.config;

/**
 * I-VE-1 VersionProfile：一个不可变的"版本画像"，打包 Mycat 对客户端自述为某版本所需的全部信息，
 * 使握手字节、能力位、{@code SELECT VERSION()} 成套联动，替代此前散落的硬编码与可变静态量。
 *
 * <p>落 {@code config} 模块（最上游叶子，protocol/hbt/proxy/server 均可直达、无环）。
 * 消费方经 {@code MetaClusterCurrent} DI 取得本画像单例，从中读取各字段。
 *
 * @param serverVersionString 版本串（握手 serverVersion、SELECT VERSION() 同源）
 * @param capabilityMask      能力位基础掩码（握手 caps；MYSQL_5_7 = getDefaultServerCapabilities()）
 * @param authPlugin          宣称的认证插件名（握手 authPluginName；首版恒 mysql_native_password）
 * @param characterSet        字符集 id（握手 charset；首版 5.7 = 8）
 * @param statusFlags         服务器状态位（首版 = 2）
 */
public record VersionProfile(String serverVersionString,
                             int capabilityMask,
                             String authPlugin,
                             int characterSet,
                             int statusFlags) {
}
