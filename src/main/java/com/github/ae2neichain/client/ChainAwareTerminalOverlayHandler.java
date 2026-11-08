package com.github.ae2neichain.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.ae2neichain.ModConfig;
import com.github.ae2neichain.debug.DebugLog;
import com.github.ae2neichain.network.ChainNetwork;
import com.github.ae2neichain.network.PacketChainCraft;
import com.github.ae2neichain.network.PacketChainCraftResult;

import codechicken.nei.NEIClientUtils;
import codechicken.nei.PositionedStack;
import codechicken.nei.api.IOverlayHandler;
import codechicken.nei.recipe.AutoCraftingManager;
import codechicken.nei.recipe.GuiOverlayButton.ItemOverlayState;
import codechicken.nei.recipe.IRecipeHandler;

/**
 * 包装 AE2 合成终端原有的 NEI overlay handler（NEE 或 AE2 自带），保留其填格/缺料显示行为， 并补上 NEI 合成链需要的
 * transferRecipe / canCraft / craft。
 */
public class ChainAwareTerminalOverlayHandler implements IOverlayHandler {

    /** 客户端→服务端自定义包上限 32767 字节，留出余量。 */
    private static final int MAX_RECIPE_BYTES = 30000;

    public final IOverlayHandler delegate;

    public ChainAwareTerminalOverlayHandler(IOverlayHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void overlayRecipe(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex, boolean maxTransfer) {
        delegate.overlayRecipe(firstGui, recipe, recipeIndex, maxTransfer);
    }

    @Override
    public int transferRecipe(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex, int multiplier) {
        delegate.overlayRecipe(firstGui, recipe, recipeIndex, multiplier != 1);
        return Math.max(1, multiplier);
    }

    @Override
    public boolean canFillCraftingGrid(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex) {
        return delegate.canFillCraftingGrid(firstGui, recipe, recipeIndex);
    }

    @Override
    public boolean requireShiftForOverlayRecipe() {
        return delegate.requireShiftForOverlayRecipe();
    }

    @Override
    public List<ItemOverlayState> presenceOverlay(GuiContainer firstGui, IRecipeHandler recipe, int recipeIndex) {
        return delegate.presenceOverlay(firstGui, recipe, recipeIndex);
    }

    @Override
    public boolean canCraft(GuiContainer firstGui, IRecipeHandler handler, int recipeIndex) {
        if (!ClientState.serverSupported || !MeInventory.isCraftingTerminal(firstGui)) return false;
        final List<PositionedStack> ingredients = handler.getIngredientStacks(recipeIndex);
        if (ingredients == null || ingredients.isEmpty()) return false;
        // getInventoryItems 已被 Mixin 扩展为“背包 + ME 网络”
        final List<ItemStack> inventory = AutoCraftingManager.getInventoryItems(firstGui).values();
        for (ItemOverlayState state : NEIClientUtils.presenceOverlay(ingredients, inventory)) {
            if (!state.isPresent()) return false;
        }
        return true;
    }

    @Override
    public boolean craft(GuiContainer firstGui, IRecipeHandler handler, int recipeIndex, int multiplier) {
        if (!ClientState.serverSupported || multiplier <= 0) return false;
        final NBTTagCompound recipe = packIngredients(handler.getIngredientStacks(recipeIndex));
        if (recipe == null) return false;

        final int id = ClientState.nextRequestId();
        final PositionedStack resultPs = handler.getResultStack(recipeIndex);
        final ItemStack result = resultPs == null ? null : resultPs.item;
        final long before = countOf(firstGui, result);

        ChainNetwork.sendToServer(new PacketChainCraft(id, multiplier, recipe));

        // 主线程调用无法阻塞等待（回执与 ME 同步都依赖主线程/网络线程推进），只发送不等待
        if (Thread.currentThread() == ClientState.mainThread) return true;

        final long deadline = System.currentTimeMillis() + ModConfig.replyTimeoutMillis;
        PacketChainCraftResult reply = null;
        while (reply == null) {
            if (Minecraft.getMinecraft().currentScreen != firstGui || System.currentTimeMillis() > deadline) {
                ClientState.RESULTS.remove(id);
                DebugLog.log("合成请求 " + id + " 未收到回执（超时或界面已关闭）");
                return false;
            }
            reply = ClientState.RESULTS.remove(id);
            if (reply == null && !sleep(2)) return false;
        }

        DebugLog.log(
                String.format(
                        "合成请求 %d: %s x%d -> crafted=%d %s",
                        id,
                        result == null ? "?" : result.getDisplayName(),
                        multiplier,
                        reply.crafted,
                        reply.error));

        if (reply.crafted <= 0) return false;

        // 等 ME 视图反映出新产物，避免合成链下一轮用旧库存做判断
        if (result != null) {
            final long expected = before + (long) reply.crafted * Math.max(1, result.stackSize);
            final long waitUntil = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < waitUntil && Minecraft.getMinecraft().currentScreen == firstGui
                    && countOf(firstGui, result) < expected) {
                if (!sleep(5)) break;
            }
        }
        return true;
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long countOf(GuiContainer gui, ItemStack stack) {
        if (stack == null) return 0;
        final ItemStack key = stack.copy();
        key.stackSize = 1;
        return AutoCraftingManager.getInventoryItems(gui).getOrDefault(key, 0L);
    }

    /** 打包为 AE2 PacketNEIRecipe 同款格式；候选过多时逐步裁剪以满足包体上限。 */
    static NBTTagCompound packIngredients(List<PositionedStack> ingredients) {
        for (int limit : new int[] { 64, 16, 4, 1 }) {
            final NBTTagCompound tag = packIngredients(ingredients, limit);
            try {
                if (CompressedStreamTools.compress(tag).length <= MAX_RECIPE_BYTES) return tag;
            } catch (IOException e) {
                return null;
            }
        }
        DebugLog.log("配方数据过大，放弃合成");
        return null;
    }

    private static NBTTagCompound packIngredients(List<PositionedStack> ingredients, int limitPerSlot) {
        final NBTTagCompound recipe = new NBTTagCompound();
        if (ingredients == null) return recipe;
        for (PositionedStack ps : ingredients) {
            final int col = (ps.relx - 25) / 18;
            final int row = (ps.rely - 6) / 18;
            if (col < 0 || col > 2 || row < 0 || row > 2) continue;

            List<ItemStack> perms = ps.getFilteredPermutations();
            if (perms == null || perms.isEmpty()) {
                perms = new ArrayList<>();
                if (ps.items != null) for (ItemStack is : ps.items) perms.add(is);
            }
            // 当前显示的那一个（合成链选中的偏好物品）放在最前
            final List<ItemStack> ordered = new ArrayList<>();
            if (ps.item != null) ordered.add(ps.item);
            for (ItemStack is : perms) {
                if (is != null && (ps.item == null || !NEIClientUtils.areStacksSameTypeCraftingWithNBT(is, ps.item))) {
                    ordered.add(is);
                }
            }

            final NBTTagList tags = new NBTTagList();
            for (ItemStack is : ordered) {
                if (tags.tagCount() >= limitPerSlot) break;
                final ItemStack copy = is.copy();
                copy.stackSize = 1;
                tags.appendTag(copy.writeToNBT(new NBTTagCompound()));
            }
            if (tags.tagCount() > 0) recipe.setTag("#" + (col + row * 3), tags);
        }
        return recipe;
    }
}
