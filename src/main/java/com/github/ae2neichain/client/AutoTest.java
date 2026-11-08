package com.github.ae2neichain.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import com.github.ae2neichain.ModConfig;
import com.github.ae2neichain.debug.DebugLog;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 全自动验收（debug=true 且 autoTest=true）：主菜单就绪后新建创造超平坦世界 → 执行 /ae2neichain e2e → 等待自测与 report 完成 → 退出游戏。
 * 结果写入 logs/ae2neichain 调试日志（AUTOTEST RESULT 行）。
 */
public final class AutoTest {

    public static final AutoTest INSTANCE = new AutoTest();
    private static final String WORLD = "ae2neichain-autotest";

    private enum Stage {
        WAIT_MENU,
        WAIT_WORLD,
        RUNNING,
        QUITTING,
        DONE
    }

    private Stage stage = Stage.WAIT_MENU;
    private int ticks;
    private volatile boolean reportReceived;

    private AutoTest() {}

    public static boolean enabled() {
        return ModConfig.debug && ModConfig.autoTest;
    }

    /** report 输出后由 ChainTestRunner 回调。 */
    public void onChainTestFinished() {
        reportReceived = true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !enabled() || stage == Stage.DONE) return;
        final Minecraft mc = Minecraft.getMinecraft();
        ticks++;
        switch (stage) {
            case WAIT_MENU:
                // 等主菜单稳定后进入
                if (mc.theWorld == null && mc.currentScreen != null && ticks > 100) {
                    DebugLog.log("AUTOTEST 创建测试世界 " + WORLD);
                    mc.getSaveLoader().flushCache();
                    mc.getSaveLoader().deleteWorldDirectory(WORLD);
                    final WorldSettings settings = new WorldSettings(
                            20261108L,
                            WorldSettings.GameType.CREATIVE,
                            false,
                            false,
                            WorldType.FLAT);
                    settings.enableCommands();
                    mc.launchIntegratedServer(WORLD, WORLD, settings);
                    stage = Stage.WAIT_WORLD;
                    ticks = 0;
                }
                break;
            case WAIT_WORLD:
                if (mc.thePlayer != null && mc.theWorld != null && ClientState.serverSupported) {
                    if (ticks > 200) {
                        DebugLog.log("AUTOTEST 进入世界，执行 /ae2neichain e2e");
                        mc.thePlayer.sendChatMessage("/ae2neichain e2e");
                        stage = Stage.RUNNING;
                        ticks = 0;
                    }
                } else {
                    ticks = 0;
                }
                if (ticks > 20 * 600) fail("等待世界加载超时");
                break;
            case RUNNING:
                if (reportReceived) {
                    if (ticks > 60) {
                        stage = Stage.QUITTING;
                        ticks = 0;
                    }
                } else if (ticks > 20 * 300) {
                    fail("合成链自测超时");
                }
                break;
            case QUITTING:
                if (ticks > 40) {
                    DebugLog.log("AUTOTEST 结束，退出游戏");
                    stage = Stage.DONE;
                    mc.shutdown();
                }
                break;
            default:
                break;
        }
    }

    private void fail(String reason) {
        DebugLog.log("AUTOTEST RESULT FAIL " + reason);
        stage = Stage.QUITTING;
        ticks = 0;
    }
}
