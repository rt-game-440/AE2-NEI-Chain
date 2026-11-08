package com.github.ae2neichain.mixins;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

import cpw.mods.fml.relauncher.FMLLaunchHandler;

@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    @Override
    public String getMixinConfig() {
        return "mixins.ae2neichain.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        if (FMLLaunchHandler.side().isClient() && loadedMods.contains("NotEnoughItems")
                && loadedMods.contains("appliedenergistics2")) {
            mixins.add("MixinAutoCraftingManager");
        }
        return mixins;
    }
}
