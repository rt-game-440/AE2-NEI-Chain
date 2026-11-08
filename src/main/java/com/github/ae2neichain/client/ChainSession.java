package com.github.ae2neichain.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import com.github.ae2neichain.debug.DebugLog;
import com.github.ae2neichain.network.PacketChainCraftResult;

import codechicken.nei.NEIClientUtils;
import codechicken.nei.bookmark.BookmarkItem;
import codechicken.nei.recipe.AutoCraftingManager;
import codechicken.nei.recipe.chain.RecipeChainMath;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 一次在合成终端里执行的 NEI 合成链：记录最终产物（决定产物去向）和每一步的回执，链结束后在聊天栏输出汇总。
 * <p>
 * begin 在主线程（runProcessing）调用；record 可能来自 NEI 后台线程或网络线程，统一加锁。
 */
public final class ChainSession {

    public static final ChainSession INSTANCE = new ChainSession();

    /** 链结束判定：NEI 不再处理且持续这么多 tick。 */
    private static final int IDLE_TICKS_TO_FINISH = 5;

    private static final class Pending {

        final ItemStack result;
        final boolean isFinal;

        Pending(ItemStack result, boolean isFinal) {
            this.result = result;
            this.isFinal = isFinal;
        }
    }

    private static final class Entry {

        final ItemStack item;
        final boolean isFinal;
        long crafted;
        long toPlayer;
        final Map<String, Integer> errors = new LinkedHashMap<>();

        Entry(ItemStack item, boolean isFinal) {
            this.item = item;
            this.isFinal = isFinal;
        }
    }

    private boolean active;
    private GuiScreen gui;
    private int idleTicks;
    private final List<ItemStack> finals = new ArrayList<>();
    private final Map<Integer, Pending> pending = new HashMap<>();
    private final List<Entry> entries = new ArrayList<>();

    private ChainSession() {}

    /** NEI 开始执行合成链（Mixin 注入 runProcessing 头部，此时 math 尚未被改造成 master root）。 */
    public synchronized void begin(RecipeChainMath math) {
        final GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (math == null || !ClientState.serverSupported || !MeInventory.isCraftingTerminal(screen)) {
            active = false;
            return;
        }
        reset();
        active = true;
        gui = screen;
        if (math.hasMasterRoot()) {
            // 重复执行同一 math：outputRecipes 只剩 root，最终产物是 root 的材料
            for (BookmarkItem item : math.recipeIngredients) {
                if (math.outputRecipes.containsKey(item.recipeId)) addFinal(item.itemStack);
            }
        } else {
            for (BookmarkItem item : math.recipeResults) {
                if (math.outputRecipes.containsKey(item.recipeId)) addFinal(item.itemStack);
            }
        }
        final StringBuilder sb = new StringBuilder("CHAINSESSION begin finals=");
        for (ItemStack is : finals) sb.append(is.getDisplayName()).append(';');
        DebugLog.log(sb.toString());
    }

    private void addFinal(ItemStack stack) {
        if (stack == null || isFinal(stack)) return;
        final ItemStack key = stack.copy();
        key.stackSize = 1;
        finals.add(key);
    }

    public synchronized boolean isFinal(ItemStack result) {
        if (result == null) return false;
        for (ItemStack is : finals) {
            if (NEIClientUtils.areStacksSameTypeCraftingWithNBT(is, result)) return true;
        }
        return false;
    }

    /** 发出合成请求前登记，回执到达时据此记账。 */
    public synchronized void onSend(int requestId, ItemStack result, boolean isFinal) {
        if (!active || result == null) return;
        pending.put(requestId, new Pending(result.copy(), isFinal));
    }

    public synchronized void onReply(PacketChainCraftResult reply) {
        final Pending p = pending.remove(reply.requestId);
        if (p == null) return;
        final Entry e = entry(p);
        e.crafted += (long) reply.crafted * Math.max(1, p.result.stackSize);
        e.toPlayer += reply.toPlayer;
        if (reply.crafted <= 0) addError(e, reply.error.isEmpty() ? "missing ingredients" : reply.error);
        else if (!reply.error.isEmpty()) addError(e, reply.error);
    }

    public synchronized void onTimeout(int requestId) {
        final Pending p = pending.remove(requestId);
        if (p != null) addError(entry(p), "timeout");
    }

    private Entry entry(Pending p) {
        for (Entry e : entries) {
            if (NEIClientUtils.areStacksSameTypeCraftingWithNBT(e.item, p.result)) return e;
        }
        final Entry e = new Entry(p.result, p.isFinal);
        entries.add(e);
        return e;
    }

    private static void addError(Entry e, String code) {
        e.errors.merge(code, 1, Integer::sum);
    }

    private void reset() {
        active = false;
        gui = null;
        idleTicks = 0;
        finals.clear();
        pending.clear();
        entries.clear();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final List<String> lines;
        synchronized (this) {
            if (!active) return;
            final boolean guiClosed = Minecraft.getMinecraft().currentScreen != gui;
            if (AutoCraftingManager.processing() && !guiClosed) {
                idleTicks = 0;
                return;
            }
            if (!guiClosed && ++idleTicks < IDLE_TICKS_TO_FINISH) return;
            lines = summarize(guiClosed && AutoCraftingManager.processing());
            reset();
        }
        final Minecraft mc = Minecraft.getMinecraft();
        for (String line : lines) {
            DebugLog.log("CHAINSUMMARY " + EnumChatFormatting.getTextWithoutFormattingCodes(line));
            if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(line));
        }
    }

    private List<String> summarize(boolean interrupted) {
        final List<String> lines = new ArrayList<>();
        final String head = EnumChatFormatting.GOLD + "[合成链] " + EnumChatFormatting.RESET;
        boolean anyCrafted = false;
        boolean anyError = false;
        for (Entry e : entries) {
            anyCrafted |= e.crafted > 0;
            anyError |= !e.errors.isEmpty();
        }

        if (entries.isEmpty()) {
            lines.add(head + EnumChatFormatting.YELLOW + "没有执行任何合成：材料不足，或配方不能在合成终端里合成");
            return lines;
        }
        if (interrupted) {
            lines.add(head + EnumChatFormatting.YELLOW + "界面已关闭，合成链中断");
        } else if (anyError) {
            lines.add(head + (anyCrafted ? EnumChatFormatting.YELLOW + "部分完成" : EnumChatFormatting.RED + "合成失败"));
        } else {
            lines.add(head + EnumChatFormatting.GREEN + "完成");
        }

        // 最终产物在前，中间产物在后
        final List<Entry> ordered = new ArrayList<>();
        for (Entry e : entries) if (e.isFinal) ordered.add(e);
        for (Entry e : entries) if (!e.isFinal) ordered.add(e);
        for (Entry e : ordered) {
            final StringBuilder sb = new StringBuilder("  ");
            sb.append(e.isFinal ? EnumChatFormatting.WHITE : EnumChatFormatting.GRAY);
            sb.append(e.item.getDisplayName()).append(" ×").append(e.crafted);
            sb.append(EnumChatFormatting.GRAY).append("  ");
            if (e.crafted > 0) sb.append(destination(e));
            for (Map.Entry<String, Integer> err : e.errors.entrySet()) {
                sb.append(EnumChatFormatting.RED).append("  ").append(describeError(err.getKey()));
                if (err.getValue() > 1) sb.append(" ×").append(err.getValue());
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    private static String destination(Entry e) {
        if (!e.isFinal) return "中间产物 → ME";
        if (e.toPlayer >= e.crafted) return "最终产物 → 背包";
        if (e.toPlayer <= 0) return "最终产物 → ME（背包已满）";
        return "最终产物 → 背包 " + e.toPlayer + "，ME " + (e.crafted - e.toPlayer) + "（背包已满）";
    }

    static String describeError(String code) {
        switch (code) {
            case "missing ingredients":
                return "材料不足";
            case "result mismatch":
                return "合成结果与配方不符";
            case "no matching recipe":
            case "recipe has no result":
                return "服务端找不到该配方";
            case "no permission":
                return "没有 ME 网络的提取/存入权限";
            case "no grid":
            case "grid not ready":
                return "ME 网络未就绪";
            case "not a crafting terminal":
            case "terminal does not use real items":
            case "no crafting output slot":
            case "no crafting matrix":
                return "当前界面不是合成终端";
            case "timeout":
                return "服务端无响应";
            default:
                return code;
        }
    }
}
