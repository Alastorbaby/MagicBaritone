/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.command.defaults;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.SettingsUtil;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.process.elytra.ElytraBehavior;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * #slime true|false
 *
 * true  = 原版鞘翅寻路（滑翔物理，不做任何修改，默认）
 * false = 接管：按 slime 甲飞+飞行控制的创造式物理走新的寻路（模拟 WASD/空格/Shift）
 */
public class SlimeCommand extends Command {

    public SlimeCommand(IBaritone baritone) {
        super(baritone, "slime");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (args.hasAny()) {
            final String v = args.getString().toLowerCase();
            final boolean vanilla;
            if (v.equals("true") || v.equals("1") || v.equals("vanilla")) {
                vanilla = true;
            } else if (v.equals("false") || v.equals("0") || v.equals("slime")) {
                vanilla = false;
            } else {
                throw new CommandInvalidStateException("用法: #slime true|false");
            }
            ElytraBehavior.slimePhysics = vanilla;
            // ★写进 Baritone 的持久化设置，并【立即落盘】到 baritone/settings.txt。
            //   只改 value 不 save 的话，重启会被 readAndApply 覆盖回旧值 ——
            //   所以这里必须显式 save（#set 命令内部也是这么做的）。
            //   注意：不能写全限定名 baritone.api.BaritoneAPI —— 本类有个同名字段 baritone，
            //   编译器会把它当成"字段 baritone 的 api 属性"，直接报找不到符号。
            BaritoneAPI.getSettings().slimePhysics.value = vanilla;
            SettingsUtil.save(BaritoneAPI.getSettings());
            if (vanilla) {
                ElytraBehavior.slimeWantForward = false;
                ElytraBehavior.slimeWantJump = false;
                ElytraBehavior.slimeWantSneak = false;
                logDirect("已切回原版鞘翅寻路（滑翔物理）");
            } else {
                logDirect("已接管：按创造式物理寻路（模拟 WASD/空格/Shift，不接管俯仰）");
            }
        } else {
            logDirect("当前模式: " + (ElytraBehavior.slimePhysics
                    ? "原版鞘翅寻路 (true)" : "接管·创造式物理 (false)"));
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        final TabCompleteHelper helper = new TabCompleteHelper().append("true", "false");
        if (args.hasAny()) {
            return helper.filterPrefix(args.getString()).stream();
        }
        return helper.stream();
    }

    @Override
    public String getShortDesc() {
        return "切换鞘翅寻路模式";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "切换鞘翅（nether）寻路用的物理模型。",
                "",
                "用法:",
                "> #slime true  - 原版鞘翅寻路（滑翔物理，默认）",
                "> #slime false - 接管：按创造式物理寻路（适配 slime 甲飞+飞行控制）",
                "> #slime       - 查看当前模式"
        );
    }
}
