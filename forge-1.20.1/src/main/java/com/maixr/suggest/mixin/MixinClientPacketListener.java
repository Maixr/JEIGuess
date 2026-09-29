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
 * ★ 必须用 SRG 名（m_xxxxx_）：生产环境的 Mixin 按 SRG 名匹配，写 official 名会直接崩溃
 *   （InvalidInjectionException: could not find any targets matching 'sendCommand'）。
 *
 * ★ 1.20.1 有三个发送路径，都要拦：
 *     sendCommand         (String)V  → m_246623_
 *     sendUnsignedCommand (String)Z  → m_246979_   ← ClickEvent 的 RUN_COMMAND 实际走这个
 *     sendChat            (String)V  → m_246175_   ← 聊天文本（顺带拦，防止别的路径漏网）
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "m_246623_", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptCommand(String command, CallbackInfo ci) {
        if (ChatHighlighter.tryHandleLinkCommand(command)) ci.cancel();
    }

    @Inject(method = "m_246979_", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptUnsignedCommand(String command, CallbackInfoReturnable<Boolean> cir) {
        if (ChatHighlighter.tryHandleLinkCommand(command)) cir.setReturnValue(Boolean.TRUE);
    }

    @Inject(method = "m_246175_", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptChat(String message, CallbackInfo ci) {
        if (ChatHighlighter.tryHandleLinkCommand(message)) ci.cancel();
    }
}
