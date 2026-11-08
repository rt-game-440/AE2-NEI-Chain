package com.github.ae2neichain;

import com.github.ae2neichain.network.PacketChainCraftResult;

/** 服务端（及公共）代理；客户端专属逻辑在 ClientProxy 中覆写。 */
public class CommonProxy {

    public void preInit() {}

    public void init() {}

    public void onServerHello() {}

    public void onCraftResult(PacketChainCraftResult msg) {}

    public void onRunChainTest() {}
}
