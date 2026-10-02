package com.maixr.suggest.mixin;

import com.maixr.suggest.ChatHighlighter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦截"发往服务器的命令"。
 *
 * ★ 必须用【官方名】(Mojang mappings)，不是 SRG 名！
 *
 *   NeoForge 1.20.2 起，运行时直接使用官方映射（Mojang mappings）。
 *   SRG 名（m_xxxxx_）是 1.20.1 及更早的产物，在 1.21.1 里**根本不存在**。
 *
 *   写 SRG 名的后果（实测于 2026-10-02）：
 *     InvalidInjectionException: @Inject on jeiguess$interceptCommand
 *       could not find any targets matching 'm_246623_'
 *   → mod 状态变 broken → 资源加载失败 → 主菜单初始化时连锁崩溃。
 *
 *   正确写法见下方；方法名直接用 javap 从 client jar 读出来确认：
 *     public void    sendCommand(String)          // 发送命令
 *     public boolean sendUnsignedCommand(String)  // 未签名命令（ClickEvent RUN_COMMAND 走这条）
 *     public void    sendChat(String)             // 聊天文本
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptCommand(String command, CallbackInfo ci) {
        if (ChatHighlighter.tryHandleLinkCommand(command)) ci.cancel();
    }

    @Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptUnsignedCommand(String command, CallbackInfoReturnable<Boolean> cir) {
        if (ChatHighlighter.tryHandleLinkCommand(command)) cir.setReturnValue(Boolean.TRUE);
    }

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptChat(String message, CallbackInfo ci) {
        if (ChatHighlighter.tryHandleLinkCommand(message)) ci.cancel();
    }
}
