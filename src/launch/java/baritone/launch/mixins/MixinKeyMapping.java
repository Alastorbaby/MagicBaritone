/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.launch.mixins;

import baritone.process.elytra.ElytraBehavior;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 只在「正在驾驶」时接管六个移动键，其余时刻原样放行。
 *
 *  - slime 的飞行控制读的是 mc.options.*Key.isDown()（不走 Baritone 的假输入通道）；
 *  - 直接 setPressed 会被真实键盘事件随时盖掉，所以在 isDown() 出口拦截；
 *  - 同时用 setDown() 的 HEAD 记录用户真实按键，用于判定是否要让位。
 */
@Mixin(KeyMapping.class)
public class MixinKeyMapping {

    @Inject(method = "setDown", at = @At("HEAD"))
    private void onSetDown(boolean down, CallbackInfo ci) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        final Object self = this;
        if (self == mc.options.keyUp) ElytraBehavior.userForward = down;
        else if (self == mc.options.keyDown) ElytraBehavior.userBack = down;
        else if (self == mc.options.keyLeft) ElytraBehavior.userLeft = down;
        else if (self == mc.options.keyRight) ElytraBehavior.userRight = down;
        else if (self == mc.options.keyJump) ElytraBehavior.userJump = down;
        else if (self == mc.options.keyShift) ElytraBehavior.userSneak = down;
    }

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void onIsDown(CallbackInfoReturnable<Boolean> cir) {
        if (!ElytraBehavior.slimeDriving) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        // 安全阀：落地就立刻放行
        if (mc.player == null || mc.player.onGround()) {
            return;
        }
        final Object self = this;
        if (self == mc.options.keyUp) {
            cir.setReturnValue(ElytraBehavior.slimeWantForward);
        } else if (self == mc.options.keyDown) {
            cir.setReturnValue(ElytraBehavior.slimeWantBack);
        } else if (self == mc.options.keyLeft) {
            cir.setReturnValue(ElytraBehavior.slimeWantLeft);
        } else if (self == mc.options.keyRight) {
            cir.setReturnValue(ElytraBehavior.slimeWantRight);
        } else if (self == mc.options.keyJump) {
            cir.setReturnValue(ElytraBehavior.slimeWantJump);
        } else if (self == mc.options.keyShift) {
            cir.setReturnValue(ElytraBehavior.slimeWantSneak);
        } else if (self == mc.options.keySprint) {
            cir.setReturnValue(false);
        }
    }
}
