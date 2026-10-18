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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 ClientPlayerEntity.tick() 结束时把玩家视角还原回去。
 *
 * 时序：
 *   Minecraft.tick() 早期   → ElytraProcess.onTick() 先存下玩家视角，再写入假朝向
 *   ClientPlayerEntity.tick() HEAD → slime 的 preProgress 读 player.getYaw()（= 假朝向）✓
 *   ...sendPosition()        → 服务端收到假朝向 ✓
 *   ClientPlayerEntity.tick() RETURN → 这里把视角还原成玩家自己的 ✓
 *   之后才走渲染             → 视口是玩家自己的朝向 ✓
 */
@Mixin(LocalPlayer.class)
public class MixinLocalPlayerViewRestore {

    /**
     * ★tick HEAD：强制写入假朝向。
     *
     * 为什么必须写在这里：鼠标转向（MouseHandler.turnPlayer → Entity.turn → setYRot(getYRot()+dx)）
     * 发生在 Minecraft.tick() 内部、player.tick() 之前。如果假朝向写得更早
     * （ElytraProcess.onTick），就会被鼠标增量叠加污染 —— 表现就是「你看哪里会影响飞行」。
     * 写在 HEAD = 紧贴 slime 读取（ClientPlayerEntity.tick 的 preProgress）之前，绝对干净。
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTickHead(CallbackInfo ci) {
        if (!ElytraBehavior.slimeDriving || !ElytraBehavior.slimeForceFakeRotation) {
            return;
        }
        final LocalPlayer self = (LocalPlayer) (Object) this;
        // ★只有确认上一次写出的假朝向已经还原回去，才敢把「当前朝向」当成玩家真视角存下来。
        //   否则某次还原漏掉时，会把假朝向存成"真视角" —— 之后所有还原都变成顶住视口，
        //   表现就是第一人称手臂被甩到身后、看不见手。
        if (!ElytraBehavior.slimeRotationDirty) {
            ElytraBehavior.viewYaw = self.getYRot();
            ElytraBehavior.viewPitch = self.getXRot();
        }
        // 写入假朝向：之后 slime 读 yaw、原版 travel 读 getLookAngle() 拿到的都是它
        self.setYRot(ElytraBehavior.slimeBaseYaw);
        self.setXRot(ElytraBehavior.slimeChosenPitch);
        ElytraBehavior.slimeRotationDirty = true;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void onTickReturn(CallbackInfo ci) {
        final LocalPlayer self = (LocalPlayer) (Object) this;
        this.baritone$slimeNoGravityGuard(self);
        // ★还原条件只看「这一 tick 有没有写过假朝向」（dirty），不再看 slimeDriving。
        //   原因：slimeDriving 可能在 tick 中途被置 false（落地 / 让位给用户 / 路径空…），
        //   那样 HEAD 写进去的假朝向就没人擦 —— 实体会带着假 pitch 活到渲染，
        //   第一人称手臂被甩出屏幕（= 用户说的「有时候找不到手」）。
        //   写了就必须擦，这是硬约束。
        if (!ElytraBehavior.slimeRotationDirty) {
            return;
        }
        self.setYRot(ElytraBehavior.viewYaw);
        self.setXRot(ElytraBehavior.viewPitch);
        // 渲染用的是这俩插值基准，一起还原才不会看到「平滑转过去」
        self.yRotO = ElytraBehavior.viewYaw;
        self.xRotO = ElytraBehavior.viewPitch;
        // ★头部/身体朝向也必须一起还原：游戏 tick 里会把 yHeadRot / yBodyRot 同步成 yRot，
        //   只还原 yRot 的话，假朝向的 yaw 会留在它们上面活到渲染 —— 这就是"视角仍被影响"的漏点。
        self.yHeadRot = ElytraBehavior.viewYaw;
        self.yHeadRotO = ElytraBehavior.viewYaw;
        self.yBodyRot = ElytraBehavior.viewYaw;
        self.yBodyRotO = ElytraBehavior.viewYaw;
        // ★纠正 slime 记录的"玩家朝向"。
        //   slime 的 PlayerStateManager 会从【我们发出的移动包】里回读朝向并记进 lastPitch/lastYaw
        //   —— 而包里装的正是假朝向（服务端需要它）。它之后一"恢复视角"就会把你掰到假朝向上。
        //   污染源在它那边，我们改不了它的逻辑，只能在每次还原后把它的记录同步回真视角。
        //   用反射是因为编译期没有 slime 的依赖；类/字段都存在，失败也只是静默跳过。
        this.baritone$syncSlimePlayerState();
        ElytraBehavior.slimeRotationDirty = false;
    }

    /** 把 slime 记录的玩家朝向同步为真视角（反射，失败静默） */
    @Unique
    private void baritone$syncSlimePlayerState() {
        try {
            final Class<?> c = Class.forName("me.matl114.hacks.modules.move.PlayerStateManager");
            final Object mgr = c.getField("INSTANCE").get(null);
            if (mgr == null) {
                return;
            }
            c.getField("lastPitch").setFloat(mgr, ElytraBehavior.viewPitch);
            c.getField("lastYaw").setFloat(mgr, ElytraBehavior.viewYaw);
        } catch (Throwable ignored) {
            // slime 不在 / 字段改名 —— 都不影响飞行
        }
    }

    /**
     * ★「断管窗口」兜底（2026-10-09）。
     *
     * slime 只在「有活烟花，或距最后一发消失 ≤ rocket-extra-effect-ticks」的 tick 才接管速度
     * （ElytraFlight: packetMotion ← canFireworkControlMotion），也只有那时才会按
     * horizontal-no-gravity 设 noGravity。烟花一断档，那几 tick 既不给速度、也不开 noGravity，
     * 原版鞘翅的重力 g*(1 - 0.75cos²pitch) 就直接裸奔 —— 实测 pitch=0 每 tick 掉 0.02、
     * pitch=90 每 tick 掉 0.08（4 倍），悬停久了再起步一次能掉 3~4 格，是坠岩浆的元凶。
     *
     * 补在 LocalPlayer.tick() 的 RETURN，原因有两条：
     *   1. 它是整 tick 最晚的点（宽于 slime 的 postModify），所以这里设的值作用于下一 tick 的 travel；
     *   2. 它不依赖 Baritone 是否在驾驶 —— 纯手动甲飞时同样生效。
     *
     * 判据刻意收窄到「slime 甲飞」：胸甲是真鞘翅时一律放行，避免改掉原版鞘翅的手感。
     */
    @Unique
    private void baritone$slimeNoGravityGuard(LocalPlayer self) {
        if (ElytraBehavior.slimeForceNoGravity
                && self.isFallFlying()
                && !self.onGround()
                && !self.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA)
                && Math.abs(self.getDeltaMovement().y) <= ElytraBehavior.slimeNoGravityMaxVy) {
            if (!self.isNoGravity()) {
                self.setNoGravity(true);
            }
            ElytraBehavior.slimeNoGravityApplied = true;
        } else if (ElytraBehavior.slimeNoGravityApplied) {
            // 落地 / 取消滑翔 / 换了真鞘翅 / 转入垂直机动 —— 一律复位，绝不留下永久悬浮
            self.setNoGravity(false);
            ElytraBehavior.slimeNoGravityApplied = false;
        }
    }
}
