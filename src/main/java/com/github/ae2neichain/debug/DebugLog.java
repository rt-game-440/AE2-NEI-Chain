package com.github.ae2neichain.debug;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.ae2neichain.ModConfig;

/**
 * 调试日志：debug=true 时写入 .minecraft/logs/ae2neichain/yyyy-MM-dd HH-mm-ss.log（一次启动一个文件）， 同时总是转发到 FML 日志。
 */
public final class DebugLog {

    public static final Logger LOG = LogManager.getLogger("ae2neichain");
    private static File logDir;
    private static PrintWriter writer;

    private DebugLog() {}

    public static void init(File mcDir) {
        logDir = new File(mcDir, "logs/ae2neichain");
    }

    public static synchronized void log(String message) {
        LOG.info(message);
        if (!ModConfig.debug || logDir == null) return;
        try {
            if (writer == null) {
                if (!logDir.isDirectory() && !logDir.mkdirs()) return;
                String name = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date()) + ".log";
                writer = new PrintWriter(
                        new OutputStreamWriter(new FileOutputStream(new File(logDir, name), true), StandardCharsets.UTF_8),
                        true);
            }
            writer.println(
                    new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + " ["
                            + Thread.currentThread().getName()
                            + "] "
                            + message);
        } catch (Exception e) {
            LOG.warn("写入调试日志失败", e);
        }
    }

    public static void error(String message, Throwable t) {
        LOG.error(message, t);
        log("ERROR " + message + ": " + t);
    }
}
