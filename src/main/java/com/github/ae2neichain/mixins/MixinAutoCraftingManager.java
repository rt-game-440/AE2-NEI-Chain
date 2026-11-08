package com.github.ae2neichain.mixins;

import net.minecraft.client.gui.inventory.GuiContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.ae2neichain.client.ChainSession;

import com.github.ae2neichain.client.MeInventory;

import codechicken.nei.ItemStackAmount;
import codechicken.nei.recipe.AutoCraftingManager;
import codechicken.nei.recipe.chain.RecipeChainMath;

/** 让 NEI 合成链（及合成树、可合成面板等）在 AE2 合成终端里把 ME 网络库存也算作“已有物品”。 */
@Mixin(value = AutoCraftingManager.class, remap = false)
public class MixinAutoCraftingManager {

    @Inject(method = "getInventoryItems", at = @At("RETURN"))
    private static void ae2neichain$appendNetworkItems(GuiContainer guiContainer,
            CallbackInfoReturnable<ItemStackAmount> cir) {
        MeInventory.appendNetworkItems(guiContainer, cir.getReturnValue());
    }

    /** 记录合成链的最终产物，并在链结束后输出聊天栏汇总。 */
    @Inject(method = "runProcessing", at = @At("HEAD"))
    private static void ae2neichain$beginSession(RecipeChainMath math, CallbackInfo ci) {
        ChainSession.INSTANCE.begin(math);
    }
}
