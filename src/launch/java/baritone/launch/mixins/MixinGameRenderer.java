/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.launch.mixins;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * ★已停用（保留文件以便对照，未在 mixins.baritone.json 中注册）。
 *
 * 曾经在这里做「渲染入口把实体朝向对齐回玩家视口」。结果是把视角钉死了：
 *   它只在 slimeForceFakeRotation 时判断返回，【漏了 slimeDriving】——
 *   于是不驾驶时也每帧把朝向设回 viewYaw，而 viewYaw 只在驾驶时更新，
 *   那个陈旧值就把玩家的鼠标转向每帧抹掉一次，视角完全动不了。
 *
 * 教训：任何"每帧无条件写实体朝向"的保险都是危险的 ——
 * 只要它依赖的那个缓存值可能过期，就会变成"每帧撤销玩家的输入"。
 *
 * 视角隔离现在只靠两处，都在 tick 边界上（不跨帧，不可能吞输入）：
 *   1. LocalPlayer.tick() RETURN —— 还原 yRot/xRot/yRotO/xRotO/yHeadRot/yHeadRotO/yBodyRot/yBodyRotO
 *      ，并把 slime 记录的 lastPitch/lastYaw 同步回真视角；
 *   2. Minecraft.tick() RETURN —— 兜底，dirty 未清时再还原一遍。
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {
}
