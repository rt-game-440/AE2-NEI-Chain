package com.github.ae2neichain.client;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import com.github.ae2neichain.debug.DebugLog;

import appeng.api.storage.data.IAEItemStack;
import appeng.client.gui.implementations.GuiMEMonitorable;
import appeng.container.slot.SlotCraftingTerm;
import appeng.container.slot.SlotPatternTerm;
import appeng.helpers.IContainerCraftingPacket;
import codechicken.nei.ItemStackAmount;

/** 客户端：识别 AE2 合成终端 GUI，并读取其 ME 库存视图（GuiMEMonitorable.repo → ItemRepo.list）。 */
public final class MeInventory {

    private static Field repoField;
    private static Field listField;
    private static boolean reflectionFailed;

    private MeInventory() {}

    /** 是否为带真实合成格的 AE2 合成终端（方块终端或 WCT），排除样板终端。 */
    public static boolean isCraftingTerminal(GuiScreen gui) {
        if (!(gui instanceof GuiMEMonitorable)) return false;
        final GuiContainer gc = (GuiContainer) gui;
        if (!(gc.inventorySlots instanceof IContainerCraftingPacket)) return false;
        for (Object slot : gc.inventorySlots.inventorySlots) {
            if (slot instanceof SlotCraftingTerm && !(slot instanceof SlotPatternTerm)) return true;
        }
        return false;
    }

    /** 把终端中 ME 网络的物品数量累加进 NEI 的库存统计。 */
    public static void appendNetworkItems(GuiContainer gui, ItemStackAmount amount) {
        if (amount == null || !isCraftingTerminal(gui)) return;
        for (IAEItemStack ais : snapshot(gui)) {
            final ItemStack is = ais.getItemStack();
            if (is != null && ais.getStackSize() > 0) {
                is.stackSize = 1;
                amount.add(is, ais.getStackSize());
            }
        }
    }

    /** 拷贝一份 ME 物品列表；列表可能被网络线程并发修改，失败重试。 */
    public static List<IAEItemStack> snapshot(GuiContainer gui) {
        final List<IAEItemStack> out = new ArrayList<>();
        final Iterable<?> list = repoList(gui);
        if (list == null) return out;
        for (int attempt = 0; attempt < 3; attempt++) {
            out.clear();
            try {
                for (Object o : list) {
                    if (o instanceof IAEItemStack) out.add(((IAEItemStack) o).copy());
                }
                return out;
            } catch (RuntimeException ignored) {
                // ConcurrentModificationException 等，重试
            }
        }
        return out;
    }

    private static Iterable<?> repoList(GuiContainer gui) {
        if (reflectionFailed) return null;
        try {
            if (repoField == null) {
                repoField = GuiMEMonitorable.class.getDeclaredField("repo");
                repoField.setAccessible(true);
            }
            final Object repo = repoField.get(gui);
            if (repo == null) return null;
            if (listField == null) {
                listField = repo.getClass().getDeclaredField("list");
                listField.setAccessible(true);
            }
            return (Iterable<?>) listField.get(repo);
        } catch (Exception e) {
            reflectionFailed = true;
            DebugLog.error("读取 AE2 终端 ME 库存失败，合成链将看不到 ME 物品", e);
            return null;
        }
    }
}
