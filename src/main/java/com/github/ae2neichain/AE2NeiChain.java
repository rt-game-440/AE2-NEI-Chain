package com.github.ae2neichain;

import java.util.Map;

import net.minecraftforge.common.MinecraftForge;

import com.github.ae2neichain.debug.DebugCommand;
import com.github.ae2neichain.debug.DebugLog;
import com.github.ae2neichain.network.ChainNetwork;
import com.github.ae2neichain.server.ServerEvents;
import com.github.ae2neichain.server.TerminalChainCrafter;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;

@Mod(
        modid = AE2NeiChain.MODID,
        name = "AE2 NEI Chain",
        version = "1.1.0",
        dependencies = "required-after:NotEnoughItems;required-after:appliedenergistics2;after:NotEnoughEnergistics;after:ae2wct")
public class AE2NeiChain {

    public static final String MODID = "ae2neichain";

    @SidedProxy(
            clientSide = "com.github.ae2neichain.client.ClientProxy",
            serverSide = "com.github.ae2neichain.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ModConfig.load(event.getSuggestedConfigurationFile());
        DebugLog.init(event.getModConfigurationDirectory().getParentFile());
        ChainNetwork.init();
        proxy.preInit();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(TerminalChainCrafter.INSTANCE);
        ServerEvents events = new ServerEvents();
        FMLCommonHandler.instance().bus().register(events);
        MinecraftForge.EVENT_BUS.register(events);
        proxy.init();
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (ModConfig.debug) {
            event.registerServerCommand(new DebugCommand());
        }
    }

    /** 任一端缺少本 mod 也允许连接；服务端缺失时客户端不会接管合成链。 */
    @NetworkCheckHandler
    public boolean checkRemote(Map<String, String> mods, Side side) {
        return true;
    }
}
