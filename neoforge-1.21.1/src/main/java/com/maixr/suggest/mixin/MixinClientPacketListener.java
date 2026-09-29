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
 * ★ 1.20.1（Forge）必须写 SRG 名（m_246623_ 之类），
 *   1.21.1（NeoForge）运行时就是官方名，直接写 sendCommand / sendUnsignedCommand / sendChat。
 *
 * 1.21.1 有三条发送路径，都要拦：
 *   sendCommand         (String)V  ← 聊天栏输入的命令
 *   sendUnsignedCommand (String)Z  ← ClickEvent 的 RUN_COMMAND 实际走这个
 *   sendChat            (String)V  ← 普通聊天文本（顺带拦，防止别的路径漏网）
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptCommand(String command, CallbackInfo ci) {
        if (ChatHighlighter.tryHandleLinkCommand(command)) ci.cancel();
    }

    @Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptUnsignedCommand(String command, CallbackInfoReturnable<Boolean> cir) {
        if (ChatHighlighter.tryHandleLinkCommand(command)) cir.setReturnValue(true);
    }

    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void jeiguess$interceptChat(String message, CallbackInfo ci) {
        if (ChatHighlighter.tryHandleLinkCommand(message)) ci.cancel();
    }
}
