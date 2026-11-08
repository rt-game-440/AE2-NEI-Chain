package com.github.ae2neichain.client;

import net.minecraftforge.common.MinecraftForge;

import com.github.ae2neichain.CommonProxy;
import com.github.ae2neichain.debug.DebugLog;
import com.github.ae2neichain.network.PacketChainCraftResult;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit() {
        ClientState.mainThread = Thread.currentThread();
    }

    @Override
    public void init() {
        MinecraftForge.EVENT_BUS.register(new OverlayHandlerInstaller());
        FMLCommonHandler.instance().bus().register(this);
        FMLCommonHandler.instance().bus().register(ChainTestRunner.INSTANCE);
        FMLCommonHandler.instance().bus().register(AutoTest.INSTANCE);
        FMLCommonHandler.instance().bus().register(ChainSession.INSTANCE);
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientState.serverSupported = false;
        ClientState.RESULTS.clear();
    }

    @Override
    public void onServerHello() {
        ClientState.serverSupported = true;
        DebugLog.log("服务端已安装 ae2neichain，启用 ME 合成终端合成链");
    }

    @Override
    public void onCraftResult(PacketChainCraftResult msg) {
        ChainSession.INSTANCE.onReply(msg);
        ClientState.RESULTS.put(msg.requestId, msg);
    }

    @Override
    public void onRunChainTest() {
        ChainTestRunner.INSTANCE.arm();
    }
}
