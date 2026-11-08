package com.github.ae2neichain;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public final class ModConfig {

    /** 调试模式：启用 logs/ae2neichain 日志与 /ae2neichain 调试命令。 */
    public static boolean debug = false;
    /** 单次合成请求的最大合成次数。 */
    public static int maxCraftsPerRequest = 64;
    /** 客户端等待服务端回执的超时时间（毫秒）。 */
    public static int replyTimeoutMillis = 5000;

    /** 全自动验收：启动后自动建测试世界、跑合成链自测、输出结果并退出游戏（需 debug=true）。 */
    public static boolean autoTest = false;

    private ModConfig() {}

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        debug = cfg.getBoolean(
                "debug",
                "general",
                false,
                "Enable debug log (.minecraft/logs/ae2neichain) and /ae2neichain debug command");
        maxCraftsPerRequest = cfg
                .getInt("maxCraftsPerRequest", "general", 64, 1, 4096, "Max crafts per single chain-step request");
        replyTimeoutMillis = cfg.getInt(
                "replyTimeoutMillis",
                "general",
                5000,
                500,
                60000,
                "Client timeout (ms) waiting for the server craft reply");
        autoTest = cfg.getBoolean(
                "autoTest",
                "general",
                false,
                "Debug only: create a test world, run the chain self-test, log PASS/FAIL and quit the game");
        if (cfg.hasChanged()) cfg.save();
    }
}
