package com.github.ae2neichain.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import com.github.ae2neichain.debug.DebugLog;

import codechicken.nei.ItemStackSet;
import codechicken.nei.NEIClientUtils;
import codechicken.nei.PositionedStack;
import codechicken.nei.bookmark.BookmarkItem;
import codechicken.nei.bookmark.BookmarkItem.BookmarkItemType;
import codechicken.nei.recipe.AutoCraftingManager;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.Recipe.RecipeIngredient;
import codechicken.nei.recipe.chain.RecipeChainMath;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 调试自测：收到服务端 PacketRunChainTest 后，在下一次打开的合成终端中以 NEI 合成链 API 执行 “原木 → 木板 → 木棍” 两级合成链（等同于在书签组上按
 * Ctrl+Shift+合成键），结束后关闭界面并请求服务端输出 report。
 */
public final class ChainTestRunner {

    public static final ChainTestRunner INSTANCE = new ChainTestRunner();
    private static final long TARGET_STICKS = 16;

    private boolean armed;
    private boolean running;
    private int armedTicks;
    private int openTicks;
    private int runTicks;
    private int idleTicks;

    private ChainTestRunner() {}

    public void arm() {
        armed = true;
        running = false;
        armedTicks = openTicks = runTicks = idleTicks = 0;
        DebugLog.log("CHAINTEST armed");
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || (!armed && !running)) return;
        final Minecraft mc = Minecraft.getMinecraft();

        if (armed) {
            if (++armedTicks > 1200) {
                armed = false;
                DebugLog.log("CHAINTEST FAIL: 60 秒内没有打开合成终端");
                AutoTest.INSTANCE.onChainTestFinished();
                return;
            }
            if (MeInventory.isCraftingTerminal(mc.currentScreen) && ClientState.serverSupported) {
                if (++openTicks >= 40) {
                    armed = false;
                    start((GuiContainer) mc.currentScreen);
                }
            } else {
                openTicks = 0;
            }
            return;
        }

        runTicks++;
        if (AutoCraftingManager.processing()) {
            idleTicks = 0;
        } else if (++idleTicks >= 20 || runTicks > 2400) {
            running = false;
            DebugLog.log("CHAINTEST finished after " + runTicks + " ticks");
            if (mc.thePlayer != null) {
                mc.thePlayer.closeScreen();
                mc.thePlayer.sendChatMessage("/ae2neichain report final");
            }
            AutoTest.INSTANCE.onChainTestFinished();
        }
    }

    private void start(GuiContainer gui) {
        try {
            final ItemStack log = new ItemStack(Blocks.log, 1, 0);
            final ItemStack planks = new ItemStack(Blocks.planks, 1, 0);
            final ItemStack stick = new ItemStack(Items.stick, 1, 0);

            final Recipe planksRecipe = findCraftingRecipe(planks, log);
            final Recipe stickRecipe = findCraftingRecipe(stick, planks);
            if (planksRecipe == null || stickRecipe == null) {
                DebugLog.log("CHAINTEST FAIL: 找不到配方 planks=" + planksRecipe + " stick=" + stickRecipe);
                AutoTest.INSTANCE.onChainTestFinished();
                return;
            }

            final long stickPerCraft = Math.max(1, stickRecipe.getResult().stackSize);
            final List<BookmarkItem> items = new ArrayList<>();
            addRecipe(items, stickRecipe, (TARGET_STICKS + stickPerCraft - 1) / stickPerCraft);
            addRecipe(items, planksRecipe, 1);

            final RecipeChainMath math = RecipeChainMath.of(items, Collections.emptySet());
            DebugLog.log(
                    "CHAINTEST start: target sticks=" + TARGET_STICKS
                            + " stickRecipe="
                            + describe(stickRecipe)
                            + " planksRecipe="
                            + describe(planksRecipe));
            AutoCraftingManager.runProcessing(math);
            running = true;
            runTicks = idleTicks = 0;
        } catch (Throwable t) {
            DebugLog.error("CHAINTEST FAIL: 启动异常", t);
            AutoTest.INSTANCE.onChainTestFinished();
        }
    }

    private static String describe(Recipe recipe) {
        final StringBuilder sb = new StringBuilder();
        sb.append(recipe.getResult().stackSize).append('x').append(recipe.getResult().getDisplayName()).append(" <- ");
        for (RecipeIngredient ingr : recipe.getIngredients()) {
            final ItemStack is = ingr.getItemStack();
            sb.append(is == null ? "?" : is.getDisplayName()).append(", ");
        }
        return sb.toString();
    }

    private static void addRecipe(List<BookmarkItem> items, Recipe recipe, long multiplier) {
        items.add(
                BookmarkItem.builder(-1, recipe.getResult(), recipe, BookmarkItemType.RESULT).multiplier(multiplier)
                        .build());
        final ItemStackSet ingredients = new ItemStackSet();
        for (RecipeIngredient ingr : recipe.getIngredients()) {
            ingredients.add(ingr.getItemStack());
        }
        for (ItemStack ingr : ingredients.values()) {
            items.add(
                    BookmarkItem.builder(-1, ingr, recipe, BookmarkItemType.INGREDIENT).multiplier(multiplier).build());
        }
    }

    /** 找一个“工作台合成”配方：产物与 result 相同，且所有材料格的候选都包含 input。 */
    private static Recipe findCraftingRecipe(ItemStack result, ItemStack input) {
        for (ICraftingHandler handler : GuiCraftingRecipe.getCraftingHandlers("item", result)) {
            if (!"crafting".equals(handler.getOverlayIdentifier())) continue;
            for (int i = 0; i < handler.numRecipes(); i++) {
                final PositionedStack res = handler.getResultStack(i);
                if (res == null || !NEIClientUtils.areStacksSameTypeCraftingWithNBT(res.item, result)) continue;
                final List<PositionedStack> ingredients = handler.getIngredientStacks(i);
                if (ingredients == null || ingredients.isEmpty()) continue;
                boolean ok = true;
                for (PositionedStack ps : ingredients) {
                    if (!ps.contains(input)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) return Recipe.of(handler, i);
            }
        }
        return null;
    }
}
