package com.github.ae2neichain.client;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraftforge.client.event.GuiOpenEvent;

import com.github.ae2neichain.debug.DebugLog;

import codechicken.nei.api.API;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.RecipeInfo;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 打开 AE2 合成终端（方块终端或 WCT）时，把 NEI 为该 GUI 注册的合成 overlay handler 包装成 {@link ChainAwareTerminalOverlayHandler}。
 * 延迟到打开时再做，避开与 NEE / AE2 NEI 插件加载顺序的竞争。
 */
public class OverlayHandlerInstaller {

    private static final String[] IDENTS = { "crafting", "crafting2x2" };

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (!(event.gui instanceof GuiContainer) || !MeInventory.isCraftingTerminal(event.gui)) return;
        final GuiContainer gui = (GuiContainer) event.gui;
        for (String ident : IDENTS) {
            final IOverlayHandler current = RecipeInfo.getOverlayHandler(gui, ident);
            if (current != null && !(current instanceof ChainAwareTerminalOverlayHandler)) {
                API.registerGuiOverlayHandler(gui.getClass(), new ChainAwareTerminalOverlayHandler(current), ident);
                DebugLog.log(
                        "已为 " + gui.getClass().getName()
                                + " ["
                                + ident
                                + "] 包装 overlay handler: "
                                + current.getClass().getName());
            }
        }
    }
}
