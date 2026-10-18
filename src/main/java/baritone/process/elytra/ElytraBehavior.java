/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process.elytra;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.*;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.ElytraProcess;
import baritone.utils.BlockStateInterface;
import baritone.utils.IRenderer;
import baritone.utils.PathRenderer;
import baritone.utils.accessor.IFireworkRocketEntity;
import com.mojang.blaze3d.vertex.BufferBuilder;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.*;
import java.util.List;
import java.util.Queue;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;

import static baritone.utils.BaritoneMath.fastCeil;
import static baritone.utils.BaritoneMath.fastFloor;

public final class ElytraBehavior implements Helper {

    // ==================== ★接管开关（#slime true|false） ====================
    /** true = 原版鞘翅寻路（不做任何修改）；false = 用 slime 甲飞+飞控的创造式物理重新接管 */
    public static volatile boolean slimePhysics = true;

    /**
     * ★接管模式下「我们想要按住的键」。
     * slime 的飞控只认 mc.options.*Key.isPressed()，所以不能走 Baritone 的假输入通道；
     * 由 MixinKeyMapping 在每 tick 的 KeyMapping.setAll() 之后强制盖成这三个值，
     * 这样用户按真实键盘也盖不掉。
     */
    public static volatile boolean slimeWantForward = false;
    public static volatile boolean slimeWantBack = false;
    public static volatile boolean slimeWantLeft = false;
    public static volatile boolean slimeWantRight = false;
    public static volatile boolean slimeWantJump = false;
    public static volatile boolean slimeWantSneak = false;

    /** 候选方向探测距离（格）：先用射线看看这个方向飞这么远会不会撞 */
    public static volatile double slimeProbe = 8.0D;

    /** 26 个输入组合：(sideways, up, forward)，每项 -1/0/1 */
    private static final int[][] SLIME_DIRS = baritone$buildSlimeDirs();

    private static int[][] baritone$buildSlimeDirs() {
        final java.util.List<int[]> list = new java.util.ArrayList<>();
        for (int s = -1; s <= 1; s++) {
            for (int u = -1; u <= 1; u++) {
                for (int f = -1; f <= 1; f++) {
                    if (s != 0 || u != 0 || f != 0) {
                        list.add(new int[]{s, u, f});
                    }
                }
            }
        }
        return list.toArray(new int[0][]);
    }
    /**
     * 是否正在驾驶中。只有它为 true，MixinKeyMapping 才会接管按键；
     * 取消 / 落地 / 让位给用户时必须置 false，否则用户按键会被钉死。
     */
    public static volatile boolean slimeDriving = false;

    /**
     * ★断管窗口兜底：slime 只在「有活烟花，或距最后一发烟花消失 ≤ rocket-extra-effect-ticks」
     * 的 tick 才接管速度，也只有那时才会按 horizontal-no-gravity 设 noGravity。
     * 烟花一断档，那几 tick 既不给速度、也不开 noGravity，原版鞘翅重力
     * g*(1 - 0.75cos²pitch) 就裸奔 —— 实测 pitch=0 每 tick 掉 0.02、pitch=90 掉 0.08（4 倍），
     * 悬停久了再起步一次能掉 3~4 格。这里按与 slime 完全相同的条件把 noGravity 补齐。
     * 置 false 即完全回到 slime 原本的行为（方便 A/B 对比）。
     */
    public static volatile boolean slimeForceNoGravity = true;
    /**
     * ★断管兜底的判据：|垂直速度| ≤ 这个值就算「水平档」（slime 那边的阈值是 1e-2）。
     * 取 0.2 是因为平飞档 vy≈0、爬升/下降档 vy≈±1.2，中间留足余量不会误判。
     */
    public static volatile double slimeNoGravityMaxVy = 0.2D;
    /** 是否由我们设过 noGravity（停飞后必须复位，否则落地会浮着） */
    public static volatile boolean slimeNoGravityApplied = false;

    /**
     * ★假朝向「已写出、尚未还原」标志。
     * 用途：HEAD 保存玩家真视角时，只有确认上一次已经把朝向还原回去了，才敢把当前朝向当真视角存。
     * 否则一旦某次还原漏掉，就会把假朝向当成「玩家真视角」存下来 —— 之后所有还原都变成
     * "还原成假朝向"，视口被永久顶住（表现为第一人称手臂被甩到身后、看不见手）。
     */
    public static volatile boolean slimeRotationDirty = false;

    // ==================== ★路线规划优化（2026-10-10，全部可单独开关） ====================

    /**
     * ★假朝向加固：移动时在 LocalPlayer.tick() 的 HEAD 强制写假朝向，
     * 这样用户无论怎么扭头都不会影响飞行（鼠标增量不会再污染假朝向）。
     * 置 false 回到旧行为（在 ElytraProcess.onTick 里写，会被鼠标增量叠加）。
     */
    public static volatile boolean slimeForceFakeRotation = true;

    /**
     * ★升降档滞回带（0 = 关闭，与旧逻辑完全等价）。
     * 旧逻辑按 ratio 跨过 ±tan22.5° 就立即换档，ratio 在阈值附近抖动时
     * up 会在 0/-1 之间反复变号 —— 表现就是「弹簧形下降」（平飞一下、下降一下）。
     * 加了这个带之后，换档需要比阈值再多走这么多才发生。
     */
    public static volatile double slimeUpHysteresis = 0.15D;
    /** 当前升降档（-1/0/+1），供滞回判定使用 */
    private static int slimeCurrentUp = 0;

    /**
     * ★小尺度「局部拉直 / 抄近道」—— 默认【关闭】。
     *
     * 它把已加载段里 A* 算出来的避障折线合并成更少的点。只要有半点判定偏差
     * （抽稀跳过拐点、边界判定翻转…），已加载段的避障就会被整段抹掉 ——
     * 实测表现就是「红线无视地形、一条笔直线」。
     *
     * 这是一个独立课题（小尺度抄近道），要研究时单独开，别和大尺度混在一起。
     */

    /**
     * ★大尺度：把【未加载区块】那一段路径替换成「从加载边界直连目的地」的直线。
     *
     * 原版行为：未加载区块在 A\* 眼里是空气，于是那一段是一条"笔直但沿轴优先"的粗线
     * （大尺度上表现为"先直线、后对角"）。这里把它改成从衔接点直接指向上目的地的直线。
     *
     * 自我消解：玩家前进 → 加载范围扩大 → 衔接点前移 → 直线段会被逐段换成真实的避障路径。
     */

    /**
     * ★贴近红线跟随（默认开启）。
     * 目标点取「红线上距自己约 slimeFollowDistance 格」的那个节点，它沿红线平滑前进 ——
     * 这是消除「原地转圈」的关键：目标不再跨 tick 大幅跳变，航向就不会在远点/近点之间来回掉头。
     */
    /** ★粘性容忍：目标连续这么多 tick 判定不可达才真的放弃（抗判定抖动，防原地打转） */
    public static volatile int slimeStickyFailGrace = 3;
    /** 粘性失败计数（内部状态） */
    private static int slimeStickyFailTicks = 0;

    /**
     * ★远景扫描间隔（tick）。远点扫描要打大量射线，很贵 ——
     * 所以每 tick 只「复核上一次的远点是否仍然可达」（一次检查，便宜），
     * 每隔这么多 tick 才重新做一次完整的远景扫描。
     */



    /**
     * ★本次要「冒充」的朝向（服务端朝向）。
     * 必须在 LocalPlayer.tickMovement() 之前写进实体，slime 的飞行控制才会按它算方向；
     * 视口由 slime 的 RotFixMixin / Baritone 的 POST 还原负责。
     */
    public static volatile float slimeYaw = 0.0F;
    public static volatile float slimePitch = 0.0F;

    /** 玩家自己想要的视角朝向：写假朝向之前存下来，tick 结束前还原回去 */
    public static volatile float viewYaw = 0.0F;
    public static volatile float viewPitch = 0.0F;

    /**
     * ★基准朝向：26 向的地基。
     * 不跟目标每 tick 走，而是锁定在 45° 网格上，只有「目标方向与它差超过 slimeYawLockStep」
     * 才换一格。这样 player.getYaw() 绝大部分时间恒定，slime 的容差盒形状稳定，不会被频繁拉回。
     */
    public static volatile float slimeBaseYaw = 0.0F;
    /** 换格迟滞（度） */
    public static volatile double slimeYawLockStep = 22.5D;
    /** 选中方向对应的俯仰，写进实体给 slime 用 */
    public static volatile float slimeChosenPitch = 0.0F;
    /** 上一 tick 用的输入（粘性用），-1 表示还没有 */
    private static int slimeLastInput = -1;

    /** ★ 基准朝向每 tick 最多转多少度（连续对准，但要平滑） */
    /**
     * ★基准朝向的每 tick 最大转角（度）。
     * 【默认 0 = 不限速】：直接 slimeBaseYaw = wantYaw，一次对准。
     * 假朝向本来就是「直接设一个值」，没有理由让它慢慢转过去 ——
     * 限速会导致「转不到位」：飞机沿旧方向飞过头，绿框跑到侧后方，再转、再过头，
     * 表现为绕着绿框兜圈子。> 0 时保留旧的限速行为，仅用于对比。
     */
    public static volatile double slimeYawRate = 0.0D;
    /** ★ 目标点粘性：离目标这么近就认为「到过了」，换下一个 */
    public static volatile double slimeArriveRadius = 10.0D;
    /** 粘性目标点（单实例，直接 static 便于统一切断时清理） */
    private static Vec3 slimeStickyTarget = null;
    private static int slimeStickyIndex = -1;

    /** 用户自己的真实按键状态（由 MixinKeyMapping 在 KeyMapping.setDown 时记录） */
    public static volatile boolean userForward = false;
    public static volatile boolean userBack = false;
    public static volatile boolean userLeft = false;
    public static volatile boolean userRight = false;
    public static volatile boolean userJump = false;
    public static volatile boolean userSneak = false;

    /** 供 ElytraProcess 调用 */
    public static void stopSlimeDrivingPublic() {
        stopSlimeDriving();
    }

    /** 停止驾驶并松开模拟按键 */
    public static void stopSlimeDriving() {
        slimeLastInput = -1;
        slimeStickyTarget = null;
        slimeStickyIndex = -1;
        slimeDriving = false;
        slimeCurrentUp = 0;
        slimeStickyFailTicks = 0;
        slimeHoverTicks = 0;
        slimeWantForward = false;
        slimeWantBack = false;
        slimeWantLeft = false;
        slimeWantRight = false;
        slimeWantJump = false;
        slimeWantSneak = false;
    }

    /**
     * ★悬停：松开全部模拟按键，slime 会进入 floating 原地停住（甲飞本来就能悬停）。
     * 找不到可走的路时用它代替「转圈找路」—— 玩家完全可以悬停等着地形/判定变好。
     */
    private static void slimeHover() {
        slimeWantForward = false;
        slimeWantBack = false;
        slimeWantLeft = false;
        slimeWantRight = false;
        slimeWantJump = false;
        slimeWantSneak = false;
    }

    /**
     * ★断管窗口兜底：条件与 slime 的 horizontal-no-gravity 完全一致
     * （水平档 ⇔ realVector.y == 0 ⇔ slimeChosenPitch == 0）。
     * 爬升/下降档刻意不补，保持 slime 原本「有垂直运动时让重力参与」的设计。
     */
    private void slimeApplyNoGravityRule() {
        if (slimeForceNoGravity && slimeChosenPitch == 0.0F) {
            ctx.player().setNoGravity(true);
            slimeNoGravityApplied = true;
        } else if (slimeNoGravityApplied) {
            ctx.player().setNoGravity(false);
            slimeNoGravityApplied = false;
        }
    }

    /** 用户是否正在自己操控移动键（含跳跃/潜行）——是的话就把控制权交还给他 */
    private static boolean slimeUserIsControlling() {
        return userForward || userBack || userLeft || userRight || userJump || userSneak;
    }

    /** 接管模式的可飞行性采样步长（格） */
    private static final double SLIME_SAMPLE_STEP = 0.5D;
    /** 玩家碰撞箱形状（宽 0.6、高 1.8）：路径后处理时用它构造「站在某节点上」的碰撞箱 */
    private static final AABB SLIME_NODE_BOX = new AABB(-0.3D, 0.0D, -0.3D, 0.3D, 1.8D, 0.3D);

    /** ★ 走廊净空半径（格）：轨迹周围这么多格内不许有方块/液体 */
    /**
     * ★脚底余量（格）：判定通行时要求玩家「脚底下方这么多格」也是空的。
     * 默认 1.0 —— 解决贴地/擦树飞行时「踩在树上罚站」。
     * 注意：这是【偏好】不是【门槛】—— 找不到时按 slimeClearanceLevels 逐级放宽，绝不会因为余量不足就不走。
     */
    public static volatile double slimeGroundClearance = 1.5D;
    /** ★两侧余量（格）：碰撞箱左右各再扩这么多（"走路要和行人留距离"） */
    public static volatile double slimeSideClearance = 0.3D;
    /**
     * ★悬停超时（tick）：连续悬停超过这么多 tick 后，强制选一个「最不坏」的点走起来，
     * 防止「判定不过 → 悬停 → 路径不变 → 永久卡死」。
     */
    public static volatile int slimeHoverTimeoutTicks = 40;
    /** 连续悬停计数（内部状态） */
    private static int slimeHoverTicks = 0;


    /** ★路径线的线宽（像素）。绘制期间临时覆盖 Baritone 的 pathRenderLineWidthPixels，画完恢复。
     *  线宽是像素单位，所以粗细固定，不会因为远近而变粗变细。 */
    public static volatile float slimeRenderLineWidth = 1.0F;
    /** ★渲染窗口向前看多少个路径节点（越大红线画得越远） */
    public static volatile int slimeRenderLookahead = 400;

    private final Baritone baritone;
    private final IPlayerContext ctx;

    // Render stuff
    private final List<Pair<Vec3, Vec3>> clearLines;
    private final List<Pair<Vec3, Vec3>> blockedLines;
    private List<Vec3> simulationLine;
    private BlockPos aimPos;
    private List<BetterBlockPos> visiblePath;

    // :sunglasses:
    public final NetherPathfinderContext context;
    public final PathManager pathManager;
    private final ElytraProcess process;

    /**
     * Remaining cool-down ticks between firework usage
     */
    private int remainingFireworkTicks;

    /**
     * Remaining cool-down ticks after the player's position and rotation are reset by the server
     */
    private int remainingSetBackTicks;

    public boolean landingMode;

    /**
     * The most recent minimum number of firework boost ticks, equivalent to {@code 10 * (1 + Flight)}
     * <p>
     * Updated every time a firework is automatically used
     */
    private int minimumBoostTicks;

    private boolean deployedFireworkLastTick;
    private final int[] nextTickBoostCounter;

    private BlockStateInterface bsi;
    private final BlockStateOctreeInterface boi;
    public final BetterBlockPos destination;
    private final boolean appendDestination;

    private final ExecutorService solverExecutor;
    private Future<Solution> solver;
    private Solution pendingSolution;
    private boolean solveNextTick;

    private long timeLastCacheCull = 0L;

    // auto swap
    private int invTickCountdown = 0;
    private final Queue<Runnable> invTransactionQueue = new LinkedList<>();

    public ElytraBehavior(Baritone baritone, ElytraProcess process, BlockPos destination, boolean appendDestination) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
        this.clearLines = new CopyOnWriteArrayList<>();
        this.blockedLines = new CopyOnWriteArrayList<>();
        this.pathManager = this.new PathManager();
        this.process = process;
        this.destination = new BetterBlockPos(destination);
        this.appendDestination = appendDestination;
        this.solverExecutor = Executors.newSingleThreadExecutor();
        this.nextTickBoostCounter = new int[2];

        this.context = new NetherPathfinderContext(Baritone.settings().elytraNetherSeed.value);
        this.boi = new BlockStateOctreeInterface(context);

        // ★从持久化设置读回"是否接管模式" —— 这样 #slime false 在重启游戏后依然生效。
        //   每次新建行为对象都同步一次，代价可以忽略。
        slimePhysics = Baritone.settings().slimePhysics.value;
    }

    public final class PathManager {

        public NetherPath path;
        private boolean completePath;
        private boolean recalculating;

        private int maxPlayerNear;
        private int ticksNearUnchanged;
        private int playerNear;

        public PathManager() {
            // lol imagine initializing fields normally
            this.clear();
        }

        public void tick() {
            // Recalculate closest path node
            this.updatePlayerNear();
            final int prevMaxNear = this.maxPlayerNear;
            this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);

            if (this.maxPlayerNear == prevMaxNear && ctx.player().isFallFlying()) {
                this.ticksNearUnchanged++;
            } else {
                this.ticksNearUnchanged = 0;
            }

            // Obstacles are more important than an incomplete path, handle those first.
            this.pathfindAroundObstacles();
            this.attemptNextSegment();
        }

        public CompletableFuture<Void> pathToDestination() {
            return this.pathToDestination(ctx.playerFeet());
        }

        public CompletableFuture<Void> pathToDestination(final BlockPos from) {
            final long start = System.nanoTime();
            // ★回归原版：A* 的搜索目标就是最终目的地，不再人为拽向"蓝线交界点"。
            //   大尺度形状交给原版那套（未加载当空气 + 分段推进）自己决定；
            //   我们只负责"选绿框 + 按方向飞"。
            return this.path0(from, ElytraBehavior.this.destination, UnaryOperator.identity())
                    .thenRun(() -> {
                        final double distance = this.path.get(0).distanceTo(this.path.get(this.path.size() - 1));
                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute path to destination");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public CompletableFuture<Void> pathRecalcSegment(final OptionalInt upToIncl) {
            if (this.recalculating) {
                throw new IllegalStateException("already recalculating");
            }

            this.recalculating = true;
            // ★回归原版：保留"汇入点之后的原路径"，因为目标也已经回到原版（目的地上游的点）
            final List<BetterBlockPos> after = upToIncl.isPresent() ? this.path.subList(upToIncl.getAsInt() + 1, this.path.size()) : Collections.emptyList();
            final boolean complete = this.completePath;

            // ★回归原版：局部重算也用最终目的地 + 保留原路径汇入点
            return this.path0(ctx.playerFeet(), upToIncl.isPresent() ? this.path.get(upToIncl.getAsInt()) : ElytraBehavior.this.destination, segment -> segment.append(after.stream(), complete || (segment.isFinished() && !upToIncl.isPresent())))
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to recompute segment");
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void pathNextSegment(final int afterIncl) {
            if (this.recalculating) {
                return;
            }

            this.recalculating = true;
            final List<BetterBlockPos> before = this.path.subList(0, afterIncl + 1);
            final long start = System.nanoTime();
            final BetterBlockPos pathStart = this.path.get(afterIncl);

            // ★回归原版：分段续算也朝最终目的地
            this.path0(pathStart, ElytraBehavior.this.destination, segment -> segment.prepend(before.stream()))
                    .thenRun(() -> {
                        final int recompute = this.path.size() - before.size() - 1;
                        final double distance = this.path.get(0).distanceTo(this.path.get(recompute));

                        if (this.completePath) {
                            logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        } else {
                            logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
                        }
                    })
                    .whenComplete((result, ex) -> {
                        this.recalculating = false;
                        if (ex != null) {
                            final Throwable cause = ex.getCause();
                            if (cause instanceof PathCalculationException) {
                                logDirect("Failed to compute next segment");
                                if (ctx.player().distanceToSqr(pathStart.getCenter()) < 16 * 16) {
                                    logVerbose("Player is near the segment start, therefore repeating this calculation is pointless. Marking as complete");
                                    completePath = true;
                                }
                            } else {
                                logUnhandledException(cause);
                            }
                        }
                    });
        }

        public void clear() {
            this.path = NetherPath.emptyPath();
            this.completePath = true;
            this.recalculating = false;
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        private void setPath(final UnpackedSegment segment) {
            List<BetterBlockPos> path = segment.collect();
            if (ElytraBehavior.this.appendDestination) {
                BlockPos dest = ElytraBehavior.this.destination;
                BlockPos last = !path.isEmpty() ? path.get(path.size() - 1) : null;
                // ★接管模式：一律【不接目的地】。
                //   因为 A* 的搜索目标已经改成"交界点"了，路径末尾天然停在加载边界上 ——
                //   这既让「末尾已加载 ⇒ 继续算下一段」的分段推进判据成立，
                //   也把"交界点之后"留给渲染层去画那条直线。
                //   一旦这里接上目的地，末尾变成未加载 → 分段推进停摆 → 路径冻结。
                if (slimePhysics && last != null && ElytraBehavior.this.clearView(
                        Vec3.atLowerCornerOf(dest), Vec3.atLowerCornerOf(last), false)) {
                    path.add(new BetterBlockPos(dest));
                }
            }
            // ★不做任何裁剪！path 必须保持 Baritone 原样。
            //   原因：Baritone 的鞘翅路径是【分段推进】的 ——
            //     attemptNextSegment(): if (!completePath && world.isLoaded(path.get(last))) pathNextSegment(last);
            //   它靠「当前段末节点已加载」来触发下一段。
            //   一旦把末尾改成目的地（几万格外、必然未加载），isLoaded(last) 永远为 false，
            //   分段推进彻底停摆 → 路径冻结 → 红线变成一条无视障碍的死直线。
            //   "未加载段直连终点"只在【渲染】层做（见 render() 里的 visiblePath）。
            // ★小尺度优化：把 A* 在网格上走出的无谓阶梯剪掉（首尾不动，不影响分段推进）
            this.path = new NetherPath(path);
            this.completePath = segment.isFinished();
            this.playerNear = 0;
            this.ticksNearUnchanged = 0;
            this.maxPlayerNear = 0;
        }

        public NetherPath getPath() {
            return this.path;
        }

        public int getNear() {
            return this.playerNear;
        }

        // mickey resigned
        private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
            return ElytraBehavior.this.context.pathFindAsync(src, dst)
                    .thenApply(UnpackedSegment::from)
                    .thenApply(operator)
                    .thenAcceptAsync(this::setPath, ctx.minecraft()::execute);
        }

        private void pathfindAroundObstacles() {
            if (this.recalculating) {
                return;
            }

            int rangeStartIncl = playerNear;
            int rangeEndExcl = playerNear;
            while (rangeEndExcl < path.size() && context.hasChunk(new ChunkPos(path.get(rangeEndExcl)))) {
                rangeEndExcl++;
            }
            // rangeEndExcl now represents an index either not in the path, or just outside render distance
            if (rangeStartIncl >= rangeEndExcl) {
                // not loaded yet?
                return;
            }
            final BetterBlockPos rangeStart = path.get(rangeStartIncl);
            if (!ElytraBehavior.this.passable(rangeStart.x, rangeStart.y, rangeStart.z, false)) {
                // we're in a wall
                return; // previous iterations of this function SHOULD have fixed this by now :rage_cat:
            }

            if (ElytraBehavior.this.process.state != ElytraProcess.State.LANDING && this.ticksNearUnchanged > 100) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1))
                        .thenRun(() -> {
                            logVerbose("Recalculating segment, no progress in last 100 ticks");
                        });
                this.ticksNearUnchanged = 0;
                return;
            }

            boolean canSeeAny = false;
            for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
                if (ElytraBehavior.this.clearView(ctx.playerFeetAsVec(), this.path.getVec(i), false) || ElytraBehavior.this.clearView(ctx.playerHead(), this.path.getVec(i), false)) {
                    canSeeAny = true;
                }
                if (!ElytraBehavior.this.clearView(this.path.getVec(i), this.path.getVec(i + 1), false)) {
                    // obstacle. where do we return to pathing?
                    // if the end of render distance is closer to goal, then that's fine, otherwise we'd be "digging our hole deeper" and making an already bad backtrack worse
                    OptionalInt rejoinMainPathAt;
                    if (this.path.get(rangeEndExcl - 1).distanceSq(ElytraBehavior.this.destination) < ctx.playerFeet().distanceSq(ElytraBehavior.this.destination)) {
                        rejoinMainPathAt = OptionalInt.of(rangeEndExcl - 1); // rejoin after current render distance
                    } else {
                        rejoinMainPathAt = OptionalInt.empty(); // large backtrack detected. ignore render distance, rejoin later on
                    }

                    final BetterBlockPos blockage = this.path.get(i);
                    final double distance = ctx.playerFeet().distanceTo(this.path.get(rejoinMainPathAt.orElse(path.size() - 1)));

                    final long start = System.nanoTime();
                    this.pathRecalcSegment(rejoinMainPathAt)
                            .thenRun(() -> {
                                logVerbose(String.format("Recalculated segment around path blockage near %s %s %s (next %.1f blocks in %.4f seconds)",
                                        SettingsUtil.maybeCensor(blockage.x),
                                        SettingsUtil.maybeCensor(blockage.y),
                                        SettingsUtil.maybeCensor(blockage.z),
                                        distance,
                                        (System.nanoTime() - start) / 1e9d
                                ));
                            });
                    return;
                }
            }
            if (!canSeeAny && rangeStartIncl < rangeEndExcl - 2 && process.state != ElytraProcess.State.GET_TO_JUMP) {
                this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1)).thenRun(() -> logVerbose("Recalculated segment since no path points were visible"));
            }
        }

        private void attemptNextSegment() {
            if (this.recalculating) {
                return;
            }

            final int last = this.path.size() - 1;
            if (!this.completePath && ctx.world().isLoaded(this.path.get(last))) {
                this.pathNextSegment(last);
            }
        }

        public void updatePlayerNear() {
            if (this.path.isEmpty()) {
                return;
            }

            int index = this.playerNear;
            final BetterBlockPos pos = ctx.playerFeet();
            for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i >= Math.max(index - 50, 0); i--) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            for (int i = index; i < Math.min(index + 50, path.size()); i++) {
                if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
                    index = i; // intentional: this changes the bound of the loop
                }
            }
            this.playerNear = index;
        }

        public boolean isComplete() {
            return this.completePath;
        }
    }

    public void onRenderPass(RenderEvent event) {

        final Settings settings = Baritone.settings();
        // ★红线/蓝线绘制期间临时覆盖 Baritone 的渲染设置，画完恢复：
        //   ① 线宽调到 slimeRenderLineWidth（像素单位，远近一样粗）
        //   ② 强制单线模式（renderPathAsLine = true）。
        //      关掉它时 Baritone 会把每段路径画成一个"扁管"——用 extraOffset(0.03格) 那层
        //      外壳包一圈。那层壳是【世界空间】尺寸，所以近看粗、远看细，
        //      这就是"蓝线有点宽 + 会变大变小"的原因。单线模式没有这层壳。
        final float prevLineWidth = settings.pathRenderLineWidthPixels.value;
        final boolean prevAsLine = settings.renderPathAsLine.value;
        settings.pathRenderLineWidthPixels.value = slimeRenderLineWidth;
        settings.renderPathAsLine.value = true;
        try {
        if (this.visiblePath != null) {
            PathRenderer.drawPath(event.getModelViewStack(), this.visiblePath, 0, Color.GREEN, false, 0, 0, 0.0D);
        }
        if (this.aimPos != null) {
            PathRenderer.drawGoal(event.getModelViewStack(), ctx, new GoalBlock(this.aimPos), event.getPartialTicks(), Color.GREEN);
        }
        if (!this.clearLines.isEmpty() && settings.elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.GREEN, settings.pathRenderLineWidthPixels.value, settings.renderPathIgnoreDepth.value);
            for (Pair<Vec3, Vec3> line : this.clearLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second());
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (!this.blockedLines.isEmpty() && Baritone.settings().elytraRenderRaytraces.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(Color.BLUE, settings.pathRenderLineWidthPixels.value, settings.renderPathIgnoreDepth.value);
            for (Pair<Vec3, Vec3> line : this.blockedLines) {
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), line.first(), line.second());
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        if (this.simulationLine != null && Baritone.settings().elytraRenderSimulation.value) {
            BufferBuilder bufferBuilder = IRenderer.startLines(new Color(0x36CCDC), settings.pathRenderLineWidthPixels.value, settings.renderPathIgnoreDepth.value);
            final Vec3 offset = ctx.player().getPosition(event.getPartialTicks());
            for (int i = 0; i < this.simulationLine.size() - 1; i++) {
                final Vec3 src = this.simulationLine.get(i).add(offset);
                final Vec3 dst = this.simulationLine.get(i + 1).add(offset);
                IRenderer.emitLine(bufferBuilder, event.getModelViewStack(), src, dst);
            }
            IRenderer.endLines(bufferBuilder, settings.renderPathIgnoreDepth.value);
        }
        } finally {
            // 恢复 Baritone 的全局渲染设置，绝不留副作用
            settings.pathRenderLineWidthPixels.value = prevLineWidth;
            settings.renderPathAsLine.value = prevAsLine;
        }
    }

    public void onChunkEvent(ChunkEvent event) {
        if (event.isPostPopulate() && this.context != null) {
            final LevelChunk chunk = ctx.world().getChunk(event.getX(), event.getZ());
            this.context.queueForPacking(chunk);
        }
    }

    public void onBlockChange(BlockChangeEvent event) {
        this.context.queueBlockUpdate(event);
    }

    public void onReceivePacket(PacketEvent event) {
        if (event.getPacket() instanceof ClientboundPlayerPositionPacket) {
            ctx.minecraft().execute(() -> {
                this.remainingSetBackTicks = Baritone.settings().elytraFireworkSetbackUseDelay.value;
            });
        }
    }

    public void pathTo() {
        if (!Baritone.settings().elytraAutoJump.value || ctx.player().isFallFlying()) {
            this.pathManager.pathToDestination();
        }
    }

    public void destroy() {
        if (this.solver != null) {
            this.solver.cancel(true);
        }
        this.solverExecutor.shutdown();
        try {
            while (!this.solverExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) {}
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        this.context.destroy();
    }

    public void repackChunks() {
        ChunkSource chunkProvider = ctx.world().getChunkSource();

        BetterBlockPos playerPos = ctx.playerFeet();

        int playerChunkX = playerPos.getX() >> 4;
        int playerChunkZ = playerPos.getZ() >> 4;

        int minX = playerChunkX - 40;
        int minZ = playerChunkZ - 40;
        int maxX = playerChunkX + 40;
        int maxZ = playerChunkZ + 40;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                LevelChunk chunk = chunkProvider.getChunk(x, z, false);

                if (chunk != null && !chunk.isEmpty()) {
                    this.context.queueForPacking(chunk);
                }
            }
        }
    }

    public void onTick() {
        synchronized (this.context.cullingLock) {
            this.onTick0();
        }
        final long now = System.currentTimeMillis();
        if ((now - this.timeLastCacheCull) / 1000 > Baritone.settings().elytraTimeBetweenCacheCullSecs.value) {
            this.context.queueCacheCulling(ctx.player().chunkPosition().x, ctx.player().chunkPosition().z, Baritone.settings().elytraCacheCullDistance.value, this.boi);
            this.timeLastCacheCull = now;
        }
    }

    private void onTick0() {
        // Fetch the previous solution, regardless of if it's going to be used
        this.pendingSolution = null;
        if (this.solver != null) {
            try {
                this.pendingSolution = this.solver.get();
            } catch (Exception ignored) {
                // it doesn't matter if get() fails since the solution can just be recalculated synchronously
            } finally {
                this.solver = null;
            }
        }

        tickInventoryTransactions();

        // Certified mojang employee incident
        if (this.remainingFireworkTicks > 0) {
            this.remainingFireworkTicks--;
        }
        if (this.remainingSetBackTicks > 0) {
            this.remainingSetBackTicks--;
        }
        if (!this.getAttachedFirework().isPresent()) {
            this.minimumBoostTicks = 0;
        }

        // Reset rendered elements
        this.clearLines.clear();
        this.blockedLines.clear();
        this.visiblePath = null;
        this.simulationLine = null;
        this.aimPos = null;

        final List<BetterBlockPos> path = this.pathManager.getPath();
        if (path.isEmpty()) {
            return;
        } else if (this.destination == null) {
            this.pathManager.clear();
            return;
        }

        // ctx AND context???? :DDD
        this.bsi = new BlockStateInterface(ctx);
        this.pathManager.tick();

        final int playerNear = this.pathManager.getNear();
        // ★渲染窗口：[玩家前方一点 .. 玩家后方一点]，最多 slimeRenderLookahead 个节点
        final int visStart = Math.max(playerNear - 30, 0);
        final int visEnd = Math.min(playerNear + slimeRenderLookahead, path.size());
        List<BetterBlockPos> vis = new ArrayList<>(path.subList(visStart, visEnd));
        this.visiblePath = vis;
    }

    /**
     * Called by {@link baritone.process.ElytraProcess#onTick(boolean, boolean)} when the process is in control and the player is flying
     */
    public void tick() {
        if (this.pathManager.getPath().isEmpty()) {
            return;
        }

        trySwapElytra();

        // ★接管模式：走创造式物理的新寻路，完全绕开原版的俯仰角求解 / 滑翔模拟
        if (!slimePhysics) {
            this.tickSlimeControl();
            return;
        }

        if (ctx.player().horizontalCollision) {
            logVerbose("hbonk");
        }
        if (ctx.player().verticalCollision) {
            logVerbose("vbonk");
        }

        final SolverContext solverContext = this.new SolverContext(false);
        this.solveNextTick = true;

        // If there's no previously calculated solution to use, or the context used at the end of last tick doesn't match this tick
        final Solution solution;
        if (this.pendingSolution == null || !this.pendingSolution.context.equals(solverContext)) {
            solution = this.solveAngles(solverContext);
        } else {
            solution = this.pendingSolution;
        }

        if (this.deployedFireworkLastTick) {
            this.nextTickBoostCounter[solverContext.boost.isBoosted() ? 1 : 0]++;
            this.deployedFireworkLastTick = false;
        }

        final boolean inLava = ctx.player().isInLava();
        if (inLava) {
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
        }

        if (solution == null) {
            logVerbose("no solution");
            return;
        }

        baritone.getLookBehavior().updateTarget(solution.rotation, false);

        if (!solution.solvedPitch) {
            logVerbose("no pitch solution, probably gonna crash in a few ticks LOL!!!");
            return;
        } else {
            this.aimPos = new BetterBlockPos(solution.goingTo.x, solution.goingTo.y, solution.goingTo.z);
        }

        this.tickUseFireworks(
                solution.context.start,
                solution.goingTo,
                solution.context.boost.isBoosted(),
                solution.forceUseFirework || inLava
        );
    }

    /**
     * ★接管模式的驾驶：假设飞行物理是「创造模式」那种 —— 方向 = 输入向量本身。
     * 所以只需要：设 yaw 对准目标 → 按 W 恒定前飞 → 用空格/Shift 控制升降。
     *
     * 刻意不接管 pitch：slime 的 shouldControlRotation 在烟花窗口里会把 pitch/yaw
     * 设成 realVector 的朝向，而我们只按 W 时 realVector 的水平方向就等于当前 yaw，
     * 所以它设的 yaw 和我们设的完全一致 —— 幂等，不会互相追。
     */
    private void tickSlimeControl() {
        // ★ 用户还在地面上：完全不接管，让他自己起跳 / 开鞘翅
        if (ctx.player() == null || ctx.player().onGround()) {
            stopSlimeDriving();
            return;
        }
        final NetherPath path = this.pathManager.getPath();
        if (path.isEmpty()) {
            stopSlimeDriving();
            return;
        }
        // ★ 用户自己在按移动键：把控制权交还给他，暂停自动寻路
        if (slimeUserIsControlling()) {
            stopSlimeDriving();
            return;
        }
        // 到这里才真正开始接管按键（不打印任何提示，避免刷聊天栏）
        slimeDriving = true;
        // ★从 Baritone 设置同步可调参数（这样 #set 之后下一 tick 就生效）
        slimeGroundClearance = Baritone.settings().slimeGroundClearance.value;
        slimeSideClearance = Baritone.settings().slimeSideClearance.value;
        final int scanNodes = Math.max(4, Baritone.settings().slimeScanNodes.value);
        final Vec3 start = ctx.player().position();
        final int near = this.pathManager.getNear();

        // ★ 目标点选取，带粘性：
        //   上次选的目标只要「还没到」且「这条路还通」，就继续用它。
        //   没有这个粘性，遇到「两层空间中间一个洞」那种地形会在洞前/洞后两个候选之间
        //   每 tick 来回跳，绿框就来回蹦、朝向跟着摆头。
        Vec3 target = null;
        if (slimeStickyIndex >= 0 && slimeStickyTarget != null
                && start.distanceTo(slimeStickyTarget) > slimeArriveRadius) {
            if (slimeCanFlyStraight(start, slimeStickyTarget)) {
                slimeStickyFailTicks = 0;
                target = slimeStickyTarget;
            } else if (slimeStickyFailTicks < slimeStickyFailGrace) {
                // ★容忍单 tick 的判定抖动：可达性检查在边界会翻转，不能一翻就换点，
                //   否则目标在远/近两点之间来回跳，飞机就原地打转。
                slimeStickyFailTicks++;
                target = slimeStickyTarget;
            }
        }
        if (target == null) {
            int chosen = -1;
                final int lo = Math.max(near, 0);
                final int hi = Math.min(lo + scanNodes, path.size() - 1);

                // ★① 远→近扫描（激进：抄近路）
                //    从看得最远的地方往回找第一个「可达」的点。
                //    找到就用它 —— 这就是"有远点就优先走远点"。
                int farChosen = -1;
                for (int i = hi; i > lo; i--) {
                    final Vec3 p = path.getVec(i);
                    if (!slimeCanFlyStraightScaled(start, p, 1.0D)) {
                        continue;
                    }
                    // ★不再做走廊净空检查：它要求轨迹两侧 1.5 格都空，会把
                    //   「贴着墙但完全过得去」的路线判成过不去（用户实测：红线贴墙能过，
                    //   从当前位置直飞的判定却过不了）。余量已经在直飞判定里体现，够了。
                    farChosen = i;
                    break;
                }

                // ★② 近→远扫描（保底：稳步推进）
                //    从近处一路往前，遇到第一个不可达就停 —— 结果一定"够得着"。
                //    ★这里用【零余量】：只要求碰撞箱本身不撞，不再要求"脚下留 1 格、两侧留 0.3"。
                //      带余量的话，连玩家脚下最近的那个节点都会被否掉（它就在脚边，
                //      脚下当然紧贴方块），于是 nearChosen = -1 → 选点失败 → 悬停 2 秒 →
                //      再走一步 → 表现就是"走一步歇一会，一分钟走不了十几格"。
                //      余量只留在上面那条"远→近"的激进路径上，保底这条必须宽松。
                int nearChosen = -1;
                for (int i = lo; i <= hi; i++) {
                    final Vec3 p = path.getVec(i);
                    if (!slimeCanFlyStraightScaled(start, p, 0.0D)) {
                        break;
                    }
                    nearChosen = i;
                }

                // ★近的也没有（狭窄地形：从当前位置直飞任何候选点都撞墙）——
                //   不要悬停！退化成"贴近红线、严格顺着它走出去"：
                //   取路径上【离玩家最近的那个节点】，只要求"玩家碰撞箱在那里放得下"，
                //   不要求从当前位置直飞得过去（窄缝里直飞必然判不过，但一格一格挪是可以的）。
                //   走出去之后，上面那条"远→近优先"的机制自然接管。
                if (farChosen < 0 && nearChosen < 0) {
                    for (int i = lo; i <= hi; i++) {
                        if (baritone$isOpenAt(path.get(i))) {
                            nearChosen = i;
                            break;
                        }
                    }
                }

                // ★③ 远的优先；远的没有就用近的
                //    近的这条一定兜得住（零余量 + 从最近处起扫），所以正常情况下
                //    不会出现"两个都没找到 → 悬停"。
                if (farChosen > 0) {
                    chosen = farChosen;
                    target = path.getVec(farChosen);
                } else if (nearChosen > 0) {
                    chosen = nearChosen;
                    target = path.getVec(nearChosen);
                }
            if (target == null) {
                // ★找不到可走的点：先悬停（松开全部模拟按键，slime 进 floating 原地停住）。
                //   但绝不能无限悬停 —— 超时后强制走一个「最不坏」的点（最近的路径节点），
                //   否则就是「判定不过 → 悬停 → 路径不变 → 永久卡死」。
                if (slimeHoverTicks < slimeHoverTimeoutTicks) {
                    slimeHoverTicks++;
                    slimeHover();
                    slimeApplyNoGravityRule();
                    this.baritone.getLookBehavior().updateTarget(new Rotation(slimeBaseYaw, slimeChosenPitch), false);
                    return;
                }
                slimeHoverTicks = 0;
                chosen = Math.min(Math.max(near, 0) + 1, path.size() - 1);
                target = path.getVec(chosen);
            }
            slimeStickyTarget = target;
            slimeStickyIndex = chosen;
        }

        // ★只要这一 tick 有目标（粘性命中或新选点都算），就说明没在悬停 —— 清理计数。
        slimeHoverTicks = 0;

        final Vec3 dir = target.subtract(start);
        if (dir.lengthSqr() < 1.0E-4) {
            return;
        }

        final float wantYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));

        // ★ 1) 倾角投影：把理想方向投影到「平面 + 上下两个 45° 圆锥面」上。
        //    方位角保持连续（交给 yaw），只有倾角离散成三档 —— 因为输入里 up 只有 -1/0/+1。
        //    这就是「任意时刻都能调出方向向量，但 pitch 只有三档、张成圆锥」的落地方式。
        final double horiz = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        final double ratio = horiz < 1.0E-6D
                ? (dir.y >= 0 ? Double.MAX_VALUE : -Double.MAX_VALUE)
                : dir.y / horiz;
        // ★ 升降档判定（带滞回）：ratio 在阈值附近抖动时不换档，
        //   否则 up 会在 0/-1 之间反复变号，表现为「弹簧形下降」。
        //   slimeUpHysteresis = 0 时与旧逻辑完全等价。
        final double hys = Math.max(0.0D, slimeUpHysteresis);
        final int up;
        if (slimeCurrentUp == 1) {
            up = ratio > SLIME_TAN_22_5 - hys ? 1 : 0;
        } else if (slimeCurrentUp == -1) {
            up = ratio < -SLIME_TAN_22_5 + hys ? -1 : 0;
        } else if (ratio > SLIME_TAN_22_5 + hys) {
            up = 1;          // 北锥：W + 空格（45° 爬升）
        } else if (ratio < -SLIME_TAN_22_5 - hys) {
            up = -1;         // 南锥：W + Shift（45° 下降）
        } else {
            up = 0;          // 平面：W（水平）
        }
        slimeCurrentUp = up;

        // ★ 2) 基准朝向连续对准目标方位角，只做变化率限制（不量化）。
        //    路径段内方位角本来就基本不变，所以这不会让 yaw 抖动；段切换时也只是平滑转过去。
        // ★不限速（默认）：直接对准目标方位角。
        //   限速会让飞机「转不到位」—— 沿旧方向飞过头、绿框跑到侧后方、再转再过头，
        //   表现就是绕着绿框兜圈子。假朝向本来就是直接设一个值，没理由慢慢转。
        slimeBaseYaw = slimeYawRate > 0.0D
                ? slimeApproachYaw(slimeBaseYaw, wantYaw, (float) slimeYawRate)
                : wantYaw;

        // ★ 3) 先试「理想方向」这一组（W / W+空格 / W+Shift）。
        //    只有它真的飞不过去，才退到 26 向里找替代（绕障）。
        final Vec3 ideal = slimeInputToWorldDir(new int[]{0, up, 1}, slimeBaseYaw);
        if (ideal != null && slimeCanFlyStraight(start, start.add(ideal.scale(slimeProbe)))) {
            slimeSetInput(0, up, 1);
        } else {
            applyBestInput(start, dir.normalize(), slimeBaseYaw);
        }

        // 3) ★ 假朝向交给 MixinLocalPlayerViewRestore 在 LocalPlayer.tick() 的 HEAD 写入。
        //    不能在这里写：鼠标转向发生在 Minecraft.tick() 内部、player.tick() 之前，
        //    这里写完会被鼠标增量叠加污染（表现为「你看哪里会影响飞行」）。
        //    这里只把「要让 Baritone 知道的朝向」登记进 LookBehavior。
        this.baritone.getLookBehavior().updateTarget(new Rotation(slimeBaseYaw, slimeChosenPitch), false);

        slimeApplyNoGravityRule();

        // 3) 绿框照旧画在这个目标点上
        this.aimPos = new BetterBlockPos(target.x, target.y, target.z);
    }

    /**
     * 在 26 个输入组合里挑一个可行的。
     *
     * 排序廉价、射线检测昂贵，所以先把 26 个候选按「与理想方向的夹角」全部排好，
     * 再从最贴近理想的开始逐个做 8 角点射线检测，第一个通过的就用 —— 正常情况 W 就过了，
     * 只有狭窄/急转弯处才会退到 W+空格、W+A、纯空格 这些候选。
     */
    private void applyBestInput(Vec3 start, Vec3 ideal, float yaw) {
        // ★ 粘性优先：上一 tick 选的方向只要这条路上还是通的，就继续用。
        //    这是「一步到位」的关键 —— 升降不做平飞/爬升交替，选定了就一路走到底，
        //    避免 up 分量反复变号导致服务端拉回。
        if (slimeLastInput >= 0 && slimeLastInput < SLIME_DIRS.length) {
            final int[] prev = SLIME_DIRS[slimeLastInput];
            final Vec3 wd = slimeInputToWorldDir(prev, yaw);
            if (wd != null && slimeCanFlyStraight(start, start.add(wd.scale(slimeProbe)))) {
                slimeApplyInput(prev, slimeLastInput);
                return;
            }
        }

        // 候选按「与理想方向的夹角」排序（廉价），再从最贴近的开始逐个做射线检测（昂贵）
        final Integer[] order = new Integer[SLIME_DIRS.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, c) -> Double.compare(
                slimeAngleTo(SLIME_DIRS[a], ideal, yaw), slimeAngleTo(SLIME_DIRS[c], ideal, yaw)));

        for (int i : order) {
            final int[] c = SLIME_DIRS[i];
            final Vec3 wd = slimeInputToWorldDir(c, yaw);
            if (wd == null) {
                continue;
            }
            if (slimeCanFlyStraight(start, start.add(wd.scale(slimeProbe)))) {
                slimeApplyInput(c, i);
                return;
            }
        }
        // 26 个方向全被挡：仍然按最贴近理想的那个走，交给下一 tick 重新评估
        slimeApplyInput(SLIME_DIRS[order[0]], order[0]);
    }

    /** tan(22.5°)：平面与圆锥的分界 */
    private static final double SLIME_TAN_22_5 = 0.41421356237309503D;

    /** 朝目标角度靠拢，每 tick 最多转 maxStep 度（走最短弧） */
    private static float slimeApproachYaw(float from, float to, float maxStep) {
        float diff = (to - from) % 360.0F;
        if (diff > 180.0F) {
            diff -= 360.0F;
        } else if (diff < -180.0F) {
            diff += 360.0F;
        }
        if (diff > maxStep) {
            diff = maxStep;
        } else if (diff < -maxStep) {
            diff = -maxStep;
        }
        return from + diff;
    }

    /** 直接按三轴值设置输入（-1/0/1） */
    private void slimeSetInput(int sideways, int up, int forward) {
        slimeWantForward = forward > 0;
        slimeWantBack = forward < 0;
        slimeWantLeft = sideways > 0;      // sidewaysSpeed(): left = +1
        slimeWantRight = sideways < 0;
        slimeWantJump = up > 0;
        slimeWantSneak = up < 0;
        slimeLastInput = -1;

        final Vec3 v = new Vec3(sideways, up, forward).normalize();
        final double h = Math.sqrt(v.x * v.x + v.z * v.z);
        final float p = (float) -Math.toDegrees(Math.atan2(v.y, h));
        slimeChosenPitch = Math.max(-90.0F, Math.min(90.0F, p));
    }

    /**
     * 某点周围 r 格内是否"空无一物"：上下左右前后各打一条射线，
     * 液体也算障碍（这样岩浆、水都会被当成墙）。
     */
    private boolean slimeClearAt(Vec3 p, double r) {
        try {
            if (ctx.world() == null || ctx.player() == null) {
                return true;
            }
            for (int axis = 0; axis < 3; axis++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    final Vec3 d = axis == 0 ? new Vec3(sign, 0, 0)
                            : axis == 1 ? new Vec3(0, sign, 0)
                            : new Vec3(0, 0, sign);
                    final net.minecraft.world.phys.HitResult hit = ctx.world().clip(
                            new net.minecraft.world.level.ClipContext(
                                    p, p.add(d.scale(r)),
                                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                    net.minecraft.world.level.ClipContext.Fluid.ANY,   // ★ 液体算障碍
                                    ctx.player()));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** ★某个位置是否"开敞"（玩家碰撞箱放得下、不撞任何方块）—— 用于筛掉落在方块里的目标点 */
    private boolean baritone$isOpenAt(final BlockPos pos) {
        if (ctx.world() == null) {
            return false;
        }
        return ctx.world().noCollision(SLIME_NODE_BOX.move(Vec3.atLowerCornerOf(pos)));
    }

    /** 角度差（度），取 0~180 */
    private static double slimeAngleDiffDeg(float a, float b) {
        double d = Math.abs(a - b) % 360.0D;
        return d > 180.0D ? 360.0D - d : d;
    }

    /** 量化到最近的 45° 网格 */
    private static float slimeQuantize45(float deg) {
        return Math.round(deg / 45.0F) * 45.0F;
    }

    private static double slimeAngleTo(int[] c, Vec3 ideal, float yaw) {
        final Vec3 wd = slimeInputToWorldDir(c, yaw);
        if (wd == null) {
            return Double.MAX_VALUE;
        }
        return Math.acos(Math.max(-1.0D, Math.min(1.0D, wd.dot(ideal))));
    }

    /**
     * 复刻 slime 的 {@code EntityUtils.movementInputToVelocity}：
     * 输入是 (sideways, up, forward)，先归一化，再按 yaw 旋转 —— 注意 y 分量不参与旋转，
     * 所以「正上/正下」是绝对方向，与视角无关。
     */
    private static Vec3 slimeInputToWorldDir(int[] c, float yaw) {
        final Vec3 v = new Vec3(c[0], c[1], c[2]);
        if (v.lengthSqr() < 1.0E-7D) {
            return null;
        }
        final Vec3 n = v.normalize();
        final double rad = Math.toRadians(yaw);
        final double sin = Math.sin(rad);
        final double cos = Math.cos(rad);
        return new Vec3(n.x * cos - n.z * sin, n.y, n.z * cos + n.x * sin);
    }

    private void slimeApplyInput(int[] c, int idx) {
        slimeWantForward = c[2] > 0;
        slimeWantBack = c[2] < 0;
        // sidewaysSpeed(): left = +1, right = -1
        slimeWantLeft = c[0] > 0;
        slimeWantRight = c[0] < 0;
        slimeWantJump = c[1] > 0;
        slimeWantSneak = c[1] < 0;
        slimeLastInput = idx;

        // 俯仰跟着「选中的方向」走，而不是跟目标连线走 —— 容差盒拿 pitch 建形状，
        // 只有它稳定，速度才不会被反复钳。纯垂直时就是 ±90°。
        final Vec3 v = new Vec3(c[0], c[1], c[2]).normalize();
        final double h = Math.sqrt(v.x * v.x + v.z * v.z);
        final float p = (float) -Math.toDegrees(Math.atan2(v.y, h));
        slimeChosenPitch = Math.max(-90.0F, Math.min(90.0F, p));
    }

    /**
     * 采样检查：从 from 直线飞到 to，玩家的碰撞箱会不会撞到方块。
     * 创造式飞行是「带体积的位移」，单条射线不够，所以把碰撞箱沿轨迹平移逐点查。
     */
    /**
     * 用玩家碰撞箱的 8 个角点各打一条射线，全部不碰才算过。
     * 对应 Baritone ElytraBehavior.isHitboxClear 的 elytraRenderHitboxRaytraces 路径，
     * 专门用来兜住「采样没抓到、实际会碰头/卡脚」的刁钻路径。
     */
    private boolean slimeHitboxRaysClear(Vec3 from, Vec3 to) {
        try {
            final AABB bb = ctx.player().getBoundingBox();
            final Vec3 shift = to.subtract(from);
            for (int corner = 0; corner < 8; corner++) {
                final double cx = (corner & 1) == 0 ? bb.minX : bb.maxX;
                final double cy = (corner & 2) == 0 ? bb.minY : bb.maxY;
                final double cz = (corner & 4) == 0 ? bb.minZ : bb.maxZ;
                final Vec3 s = new Vec3(cx, cy, cz);
                final Vec3 d = s.add(shift);
                final net.minecraft.world.phys.HitResult hit = ctx.world().clip(
                        new net.minecraft.world.level.ClipContext(
                                s, d,
                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                ctx.player()));
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean slimeCanFlyStraight(Vec3 from, Vec3 to) {
        return slimeCanFlyStraightScaled(from, to, 1.0D);
    }

    /**
     * ★按倍率缩放的通行判定（用于选点的「余量降级」）。
     * scale = 1.0 用完整余量；逐级降到 0.0 时只要求碰撞箱本身不撞。
     */
    private boolean slimeCanFlyStraightScaled(Vec3 from, Vec3 to, double scale) {
        if (ctx.world() == null || ctx.player() == null) {
            return false;   // ★ 判定不了 → 保守当作过不去，绝不能当作"可达"
        }
        return slimeCanFlyStraightWith(
                from,
                to,
                ctx.player().getBoundingBox(),
                slimeGroundClearance * scale,
                slimeSideClearance * scale);
    }

    /**
     * ★核心通行性判定：给定一个碰撞箱（箱底应对应 from 处的地面），
     * 沿 from→to 平移采样，要求全程 noCollision。
     *
     * 为什么必须由调用方传入碰撞箱：以前只有「玩家当前位置碰撞箱」这一个版本，
     * 而路径拉直拿它去判「节点 A → 节点 B」时，箱子的位置和节点 A 差着十万八千里，
     * 判定结果整个是错的 —— 这就是「拉直后的红线会穿墙」的真正原因。
     *
     * ★另一条铁律：判定不出来时一律返回 false。旧版在 world 为 null / 抛异常时返回 true，
     * 等于把「不知道」当成「能飞」，结果就是红线穿过障碍、玩家在绿框处罚站。
     *
     * ★第三条铁律：【未加载区块必须当作过不去】。
     * MC 的 noCollision 对未加载区块一律返回「无碰撞」（视作空气），
     * 于是几万格的长直线会被判成「一路畅通」，红线被拉成一条笔直线、完全无视地形。
     *
     * @param groundClearance &gt;0 时把碰撞箱向下扩这么多，即要求「脚底下方这么多格也是空的」
     */
    private boolean slimeCanFlyStraightWith(
            Vec3 from, Vec3 to, AABB box, double groundClearance, double sideClearance) {
        try {
            if (ctx.world() == null) {
                return false;
            }
            final Vec3 delta = to.subtract(from);
            final double len = delta.length();
            if (len < 1.0E-4D) {
                return true;
            }
            AABB base = box;
            if (groundClearance > 0.0D) {
                base = base.expandTowards(0.0D, -groundClearance, 0.0D);   // 脚下余量
            }
            if (sideClearance > 0.0D) {
                base = base.inflate(sideClearance, 0.0D, sideClearance);   // 两侧余量
            }
            final int steps = (int) Math.ceil(len / SLIME_SAMPLE_STEP);
            final int chunkCheckEvery = Math.max(1, (int) Math.ceil(4.0D / SLIME_SAMPLE_STEP));
            for (int i = 1; i <= steps; i++) {
                final Vec3 at = from.add(delta.scale((double) i / (double) steps));
                // ★每 4 格检查一次区块加载：未加载 → 保守当作过不去。
                //   不加这条的话，几万格的直线会整段落在未加载区块里，被当成空气 → 红线变笔直线。
                if (i % chunkCheckEvery == 0 || i == steps) {
                    if (!ctx.world().hasChunkAt(BlockPos.containing(at))) {
                        return false;
                    }
                }
                final Vec3 shift = at.subtract(from);
                final AABB probe = base.move(shift.x, shift.y, shift.z);
                // player 为 null 时退化成不带实体的 noCollision（仍然能查方块碰撞）
                final boolean blocked = ctx.player() != null
                        ? !ctx.world().noCollision(ctx.player(), probe)
                        : !ctx.world().noCollision(probe);
                if (blocked) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;   // ★ 异常同样保守处理
        }
    }

    public void onPostTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN && this.solveNextTick) {
            // We're at the end of the tick, the player's position likely updated and the closest path node could've
            // changed. Updating it now will avoid unnecessary recalculation on the main thread.
            this.pathManager.updatePlayerNear();

            final SolverContext context = this.new SolverContext(true);
            this.solver = this.solverExecutor.submit(() -> this.solveAngles(context));
            this.solveNextTick = false;
        }
    }

    private Solution solveAngles(final SolverContext context) {
        final NetherPath path = context.path;
        final int playerNear = landingMode ? path.size() - 1 : context.playerNear;
        final Vec3 start = context.start;
        Solution solution = null;

        for (int relaxation = 0; relaxation < 3; relaxation++) { // try for a strict solution first, then relax more and more (if we're in a corner or near some blocks, it will have to relax its constraints a bit)
            int[] heights = context.boost.isBoosted() ? new int[]{20, 10, 5, 0} : new int[]{0}; // attempt to gain height, if we can, so as not to waste the boost
            int lookahead = relaxation == 0 ? 2 : 3; // ideally this would be expressed as a distance in blocks, rather than a number of voxel steps
            //int minStep = Math.max(0, playerNear - relaxation);
            int minStep = playerNear;

            for (int i = Math.min(playerNear + 20, path.size() - 1); i >= minStep; i--) {
                final List<Pair<Vec3, Integer>> candidates = new ArrayList<>();
                for (int dy : heights) {
                    if (relaxation == 0 || i == minStep) {
                        // no interp
                        candidates.add(new Pair<>(path.getVec(i), dy));
                    } else if (relaxation == 1) {
                        final double[] interps = new double[]{1.0, 0.75, 0.5, 0.25};
                        for (double interp : interps) {
                            final Vec3 dest = interp == 1.0
                                    ? path.getVec(i)
                                    : path.getVec(i).scale(interp).add(path.getVec(i - 1).scale(1.0 - interp));
                            candidates.add(new Pair<>(dest, dy));
                        }
                    } else {
                        // Create a point along the segment every block
                        final Vec3 delta = path.getVec(i).subtract(path.getVec(i - 1));
                        final int steps = fastFloor(delta.length());
                        final Vec3 step = delta.normalize();
                        Vec3 stepped = path.getVec(i);
                        for (int interp = 0; interp < steps; interp++) {
                            candidates.add(new Pair<>(stepped, dy));
                            stepped = stepped.subtract(step);
                        }
                    }
                }

                for (final Pair<Vec3, Integer> candidate : candidates) {
                    final Integer augment = candidate.second();
                    Vec3 dest = candidate.first().add(0, augment, 0);
                    if (landingMode) {
                        dest = dest.add(0.5, 0.5, 0.5);
                    }

                    if (augment != 0) {
                        if (i + lookahead >= path.size()) {
                            continue;
                        }
                        if (start.distanceTo(dest) < 40) {
                            if (!this.clearView(dest, path.getVec(i + lookahead).add(0, augment, 0), false)
                                    || !this.clearView(dest, path.getVec(i + lookahead), false)) {
                                // aka: don't go upwards if doing so would prevent us from being able to see the next position **OR** the modified next position
                                continue;
                            }
                        } else {
                            // but if it's far away, allow gaining altitude if we could lose it again by the time we get there
                            if (!this.clearView(dest, path.getVec(i), false)) {
                                continue;
                            }
                        }
                    }

                    final double minAvoidance = Baritone.settings().elytraMinimumAvoidance.value;
                    final Double growth = relaxation == 2 ? null
                            : relaxation == 0 ? 2 * minAvoidance : minAvoidance;

                    if (this.isHitboxClear(context, dest, growth)) {
                        // Yaw is trivial, just calculate the rotation required to face the destination
                        final float yaw = RotationUtils.calcRotationFromVec3d(start, dest, ctx.playerRotations()).getYaw();

                        final Pair<Float, Boolean> pitch = this.solvePitch(context, dest, relaxation);
                        if (pitch == null) {
                            solution = new Solution(context, new Rotation(yaw, ctx.playerRotations().getPitch()), null, false, false);
                            continue;
                        }

                        // A solution was found with yaw AND pitch, so just immediately return it.
                        return new Solution(context, new Rotation(yaw, pitch.first()), dest, true, pitch.second());
                    }
                }
            }
        }
        return solution;
    }

    private void tickUseFireworks(final Vec3 start, final Vec3 goingTo, final boolean isBoosted, final boolean forceUseFirework) {
        // ★接管模式：创造式物理不需要烟花；原版模式完全保留原行为
        if (!slimePhysics) return;
        if (this.remainingSetBackTicks > 0) {
            logDebug("waiting for elytraFireworkSetbackUseDelay: " + this.remainingSetBackTicks);
            return;
        }
        if (this.landingMode) {
            return;
        }
        final boolean useOnDescend = !Baritone.settings().elytraConserveFireworks.value || ctx.player().position().y < goingTo.y + 5;
        final double currentSpeed = new Vec3(
                ctx.player().getDeltaMovement().x,
                // ignore y component if we are BOTH below where we want to be AND descending
                ctx.player().position().y < goingTo.y ? Math.max(0, ctx.player().getDeltaMovement().y) : ctx.player().getDeltaMovement().y,
                ctx.player().getDeltaMovement().z
        ).lengthSqr();

        final double elytraFireworkSpeed = Baritone.settings().elytraFireworkSpeed.value;
        if (this.remainingFireworkTicks <= 0 && (forceUseFirework || (!isBoosted
                && useOnDescend
                && (ctx.player().position().y < goingTo.y - 5 || start.distanceTo(new Vec3(goingTo.x + 0.5, ctx.player().position().y, goingTo.z + 0.5)) > 5) // UGH!!!!!!!
                && currentSpeed < elytraFireworkSpeed * elytraFireworkSpeed))
        ) {
            // Prioritize boosting fireworks over regular ones
            // TODO: Take the minimum boost time into account?
            if (!baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isBoostingFireworks) &&
                    !baritone.getInventoryBehavior().throwaway(true, ElytraBehavior::isFireworks)) {
                logDirect("no fireworks");
                return;
            }
            logVerbose("attempting to use firework" + (forceUseFirework ? " (forced)" : ""));
            ctx.playerController().processRightClick(ctx.player(), ctx.world(), InteractionHand.MAIN_HAND);
            this.minimumBoostTicks = 10 * (1 + getFireworkBoost(ctx.player().getItemInHand(InteractionHand.MAIN_HAND)).orElse(0));
            this.remainingFireworkTicks = 10;
            this.deployedFireworkLastTick = true;
        }
    }

    private final class SolverContext {

        public final NetherPath path;
        public final int playerNear;
        public final Vec3 start;
        public final Vec3 motion;
        public final AABB boundingBox;
        public final boolean ignoreLava;
        public final FireworkBoost boost;
        public final IAimProcessor aimProcessor;

        /**
         * Creates a new SolverContext using the current state of the path, player, and firework boost at the time of
         * construction.
         *
         * @param async Whether the computation is being done asynchronously at the end of a game tick.
         */
        public SolverContext(boolean async) {
            this.path = ElytraBehavior.this.pathManager.getPath();
            this.playerNear = ElytraBehavior.this.pathManager.getNear();

            this.start = ctx.playerFeetAsVec();
            this.motion = ctx.playerMotion();
            this.boundingBox = ctx.player().getBoundingBox();
            this.ignoreLava = ctx.player().isInLava();

            final Integer fireworkTicksExisted;
            if (async && ElytraBehavior.this.deployedFireworkLastTick) {
                final int[] counter = ElytraBehavior.this.nextTickBoostCounter;
                fireworkTicksExisted = counter[1] > counter[0] ? 0 : null;
            } else {
                fireworkTicksExisted = ElytraBehavior.this.getAttachedFirework().map(e -> e.tickCount).orElse(null);
            }
            this.boost = new FireworkBoost(fireworkTicksExisted, ElytraBehavior.this.minimumBoostTicks);

            ITickableAimProcessor aim = ElytraBehavior.this.baritone.getLookBehavior().getAimProcessor().fork();
            if (async) {
                // async computation is done at the end of a tick, advance by 1 to prepare for the next tick
                aim.advance(1);
            }
            this.aimProcessor = aim;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != SolverContext.class) {
                return false;
            }

            SolverContext other = (SolverContext) o;
            return this.path == other.path  // Contents aren't modified, just compare by reference
                    && this.playerNear == other.playerNear
                    && Objects.equals(this.start, other.start)
                    && Objects.equals(this.motion, other.motion)
                    && Objects.equals(this.boundingBox, other.boundingBox)
                    && this.ignoreLava == other.ignoreLava
                    && Objects.equals(this.boost, other.boost);
        }
    }

    private static final class FireworkBoost {

        private final Integer fireworkTicksExisted;
        private final int minimumBoostTicks;
        private final int maximumBoostTicks;

        /**
         * @param fireworkTicksExisted The ticksExisted of the attached firework entity, or {@code null} if no entity.
         * @param minimumBoostTicks    The minimum number of boost ticks that the attached firework entity, if any, will
         *                             provide.
         */
        public FireworkBoost(final Integer fireworkTicksExisted, final int minimumBoostTicks) {
            this.fireworkTicksExisted = fireworkTicksExisted;

            // this.lifetime = 10 * i + this.rand.nextInt(6) + this.rand.nextInt(7);
            this.minimumBoostTicks = minimumBoostTicks;
            this.maximumBoostTicks = minimumBoostTicks + 11;
        }

        public boolean isBoosted() {
            return this.fireworkTicksExisted != null;
        }

        /**
         * @return The guaranteed number of remaining ticks with boost
         */
        public int getGuaranteedBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.minimumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        /**
         * @return The maximum number of remaining ticks with boost
         */
        public int getMaximumBoostTicks() {
            return this.isBoosted() ? Math.max(0, this.maximumBoostTicks - this.fireworkTicksExisted) : 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || o.getClass() != FireworkBoost.class) {
                return false;
            }

            FireworkBoost other = (FireworkBoost) o;
            if (!this.isBoosted() && !other.isBoosted()) {
                return true;
            }

            return Objects.equals(this.fireworkTicksExisted, other.fireworkTicksExisted)
                    && this.minimumBoostTicks == other.minimumBoostTicks
                    && this.maximumBoostTicks == other.maximumBoostTicks;
        }
    }

    private static final class PitchResult {

        public final float pitch;
        public final double dot;
        public final List<Vec3> steps;

        public PitchResult(float pitch, double dot, List<Vec3> steps) {
            this.pitch = pitch;
            this.dot = dot;
            this.steps = steps;
        }
    }

    private static final class Solution {

        public final SolverContext context;
        public final Rotation rotation;
        public final Vec3 goingTo;
        public final boolean solvedPitch;
        public final boolean forceUseFirework;

        public Solution(SolverContext context, Rotation rotation, Vec3 goingTo, boolean solvedPitch, boolean forceUseFirework) {
            this.context = context;
            this.rotation = rotation;
            this.goingTo = goingTo;
            this.solvedPitch = solvedPitch;
            this.forceUseFirework = forceUseFirework;
        }
    }

    public static boolean isFireworks(final ItemStack itemStack) {
        if (itemStack.getItem() != Items.FIREWORK_ROCKET) {
            return false;
        }
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        return fw != null && fw.explosions().isEmpty();
    }

    private static boolean isBoostingFireworks(final ItemStack itemStack) {
        return getFireworkBoost(itemStack).isPresent();
    }

    private static OptionalInt getFireworkBoost(final ItemStack itemStack) {
        Fireworks fw = itemStack.get(DataComponents.FIREWORKS);
        if (fw != null && fw.explosions().isEmpty()) {
            return OptionalInt.of(fw.flightDuration());
        }
        return OptionalInt.empty();
    }

    private Optional<FireworkRocketEntity> getAttachedFirework() {
        return ctx.entitiesStream()
                .filter(x -> x instanceof FireworkRocketEntity)
                .filter(x -> Objects.equals(((IFireworkRocketEntity) x).getBoostedEntity(), ctx.player()))
                .map(x -> (FireworkRocketEntity) x)
                .findFirst();
    }

    private boolean isHitboxClear(final SolverContext context, final Vec3 dest, final Double growAmount) {
        final Vec3 start = context.start;
        final boolean ignoreLava = context.ignoreLava;

        if (!this.clearView(start, dest, ignoreLava)) {
            return false;
        }
        if (growAmount == null) {
            return true;
        }

        final AABB bb = context.boundingBox.inflate(growAmount);

        final double ox = dest.x - start.x;
        final double oy = dest.y - start.y;
        final double oz = dest.z - start.z;

        final double[] src = new double[]{
                bb.minX, bb.minY, bb.minZ,
                bb.minX, bb.minY, bb.maxZ,
                bb.minX, bb.maxY, bb.minZ,
                bb.minX, bb.maxY, bb.maxZ,
                bb.maxX, bb.minY, bb.minZ,
                bb.maxX, bb.minY, bb.maxZ,
                bb.maxX, bb.maxY, bb.minZ,
                bb.maxX, bb.maxY, bb.maxZ,
        };
        final double[] dst = new double[]{
                bb.minX + ox, bb.minY + oy, bb.minZ + oz,
                bb.minX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.minX + ox, bb.maxY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.minY + oy, bb.maxZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.minZ + oz,
                bb.maxX + ox, bb.maxY + oy, bb.maxZ + oz,
        };

        // Use non-batching method without early failure
        if (Baritone.settings().elytraRenderHitboxRaytraces.value) {
            boolean clear = true;
            for (int i = 0; i < 8; i++) {
                final Vec3 s = new Vec3(src[i * 3], src[i * 3 + 1], src[i * 3 + 2]);
                final Vec3 d = new Vec3(dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
                // Don't forward ignoreLava since the batch call doesn't care about it
                if (!this.clearView(s, d, false)) {
                    clear = false;
                }
            }
            return clear;
        }

        return this.context.raytrace(8, src, dst, NetherPathfinderContext.Visibility.ALL);
    }

    public boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava) {
        final boolean clear;
        if (!ignoreLava) {
            // if start == dest then the cpp raytracer dies
            clear = start.equals(dest) || this.context.raytrace(start, dest);
        } else {
            clear = ctx.world().clip(new ClipContext(start, dest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
        }

        if (Baritone.settings().elytraRenderRaytraces.value) {
            (clear ? this.clearLines : this.blockedLines).add(new Pair<>(start, dest));
        }
        return clear;
    }

    private static FloatArrayList pitchesToSolveFor(final float goodPitch, final boolean desperate) {
        final float minPitch = desperate ? -90 : Math.max(goodPitch - Baritone.settings().elytraPitchRange.value, -89);
        final float maxPitch = desperate ? 90 : Math.min(goodPitch + Baritone.settings().elytraPitchRange.value, 89);

        final FloatArrayList pitchValues = new FloatArrayList(fastCeil(maxPitch - minPitch) + 1);
        for (float pitch = goodPitch; pitch <= maxPitch; pitch++) {
            pitchValues.add(pitch);
        }
        for (float pitch = goodPitch - 1; pitch >= minPitch; pitch--) {
            pitchValues.add(pitch);
        }

        return pitchValues;
    }

    @FunctionalInterface
    private interface IntTriFunction<T> {
        T apply(int first, int second, int third);
    }

    private static final class IntTriple {
        public final int first;
        public final int second;
        public final int third;

        public IntTriple(int first, int second, int third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }

    private Pair<Float, Boolean> solvePitch(final SolverContext context, final Vec3 goal, final int relaxation) {
        final boolean desperate = relaxation == 2;
        final float goodPitch = RotationUtils.calcRotationFromVec3d(context.start, goal, ctx.playerRotations()).getPitch();
        final FloatArrayList pitches = pitchesToSolveFor(goodPitch, desperate);

        final IntTriFunction<PitchResult> solve = (ticks, ticksBoosted, ticksBoostDelay) ->
                this.solvePitch(context, goal, relaxation, pitches.iterator(), ticks, ticksBoosted, ticksBoostDelay);

        final List<IntTriple> tests = new ArrayList<>();

        if (context.boost.isBoosted()) {
            final int guaranteed = context.boost.getGuaranteedBoostTicks();
            if (guaranteed == 0) {
                // uncertain when boost will run out
                final int lookahead = Math.max(4, 10 - context.boost.getMaximumBoostTicks());
                tests.add(new IntTriple(lookahead, 1, 0));
            } else if (guaranteed <= 5) {
                // boost will run out within 5 ticks
                tests.add(new IntTriple(guaranteed + 5, guaranteed, 0));
            } else {
                // there's plenty of guaranteed boost
                tests.add(new IntTriple(guaranteed + 1, guaranteed, 0));
            }
        }

        // Standard test, assume (not) boosted for entire duration
        final int ticks = desperate ? 3 : context.boost.isBoosted() ? Math.max(5, context.boost.getGuaranteedBoostTicks()) : Baritone.settings().elytraSimulationTicks.value;
        tests.add(new IntTriple(ticks, context.boost.isBoosted() ? ticks : 0, 0));

        final Optional<PitchResult> result = tests.stream()
                .map(i -> solve.apply(i.first, i.second, i.third))
                .filter(Objects::nonNull)
                .findFirst();
        if (result.isPresent()) {
            return new Pair<>(result.get().pitch, false);
        }

        // If we used a firework would we be able to get out of the current situation??? perhaps
        if (desperate) {
            final List<IntTriple> testsBoost = new ArrayList<>();
            testsBoost.add(new IntTriple(ticks, 10, 3));
            testsBoost.add(new IntTriple(ticks, 10, 2));
            testsBoost.add(new IntTriple(ticks, 10, 1));

            final Optional<PitchResult> resultBoost = testsBoost.stream()
                    .map(i -> solve.apply(i.first, i.second, i.third))
                    .filter(Objects::nonNull)
                    .findFirst();
            if (resultBoost.isPresent()) {
                return new Pair<>(resultBoost.get().pitch, true);
            }
        }

        return null;
    }

    private PitchResult solvePitch(final SolverContext context, final Vec3 goal, final int relaxation,
                                   final FloatIterator pitches, final int ticks, final int ticksBoosted,
                                   final int ticksBoostDelay) {
        // we are at a certain velocity, but we have a target velocity
        // what pitch would get us closest to our target velocity?
        // yaw is easy so we only care about pitch

        final Vec3 goalDelta = goal.subtract(context.start);
        final Vec3 goalDirection = goalDelta.normalize();

        final Deque<PitchResult> bestResults = new ArrayDeque<>();

        while (pitches.hasNext()) {
            final float pitch = pitches.nextFloat();
            final List<Vec3> displacement = this.simulate(
                    context,
                    goalDelta,
                    pitch,
                    ticks,
                    ticksBoosted,
                    ticksBoostDelay
            );
            if (displacement == null) {
                continue;
            }
            final Vec3 last = displacement.get(displacement.size() - 1);
            double goodness = goalDirection.dot(last.normalize());
            if (landingMode) {
                goodness = -goalDelta.subtract(last).length();
            }
            final PitchResult bestSoFar = bestResults.peek();
            if (bestSoFar == null || goodness > bestSoFar.dot) {
                bestResults.push(new PitchResult(pitch, goodness, displacement));
            }
        }

        outer:
        for (final PitchResult result : bestResults) {
            if (relaxation < 2) {
                // Ensure that the goal is visible along the entire simulated path
                // Reverse order iteration since the last position is most likely to fail
                for (int i = result.steps.size() - 1; i >= 1; i--) {
                    if (!clearView(context.start.add(result.steps.get(i)), goal, context.ignoreLava)) {
                        continue outer;
                    }
                }
            } else {
                // Ensure that the goal is visible from the final position
                if (!clearView(context.start.add(result.steps.get(result.steps.size() - 1)), goal, context.ignoreLava)) {
                    continue;
                }
            }

            this.simulationLine = result.steps;
            return result;
        }
        return null;
    }

    private List<Vec3> simulate(final SolverContext context, final Vec3 goalDelta, final float pitch, final int ticks,
                                final int ticksBoosted, final int ticksBoostDelay) {
        final ITickableAimProcessor aimProcessor = context.aimProcessor.fork();
        Vec3 delta = goalDelta;
        Vec3 motion = context.motion;
        AABB hitbox = context.boundingBox;
        List<Vec3> displacement = new ArrayList<>(ticks + 1);
        displacement.add(Vec3.ZERO);
        int remainingTicksBoosted = ticksBoosted;

        for (int i = 0; i < ticks; i++) {
            final double cx = hitbox.minX + (hitbox.maxX - hitbox.minX) * 0.5D;
            final double cz = hitbox.minZ + (hitbox.maxZ - hitbox.minZ) * 0.5D;
            if (delta.lengthSqr() < 1) {
                break;
            }
            final Rotation rotation = aimProcessor.nextRotation(
                    RotationUtils.calcRotationFromVec3d(Vec3.ZERO, delta, ctx.playerRotations()).withPitch(pitch)
            );
            final Vec3 lookDirection = RotationUtils.calcLookDirectionFromRotation(rotation);

            motion = step(motion, lookDirection, rotation.getPitch());
            delta = delta.subtract(motion);

            // Collision box while the player is in motion, with additional padding for safety
            final AABB inMotion = hitbox.inflate(motion.x, motion.y, motion.z).inflate(0.01);

            int xmin = fastFloor(inMotion.minX);
            int xmax = fastCeil(inMotion.maxX);
            int ymin = fastFloor(inMotion.minY);
            int ymax = fastCeil(inMotion.maxY);
            int zmin = fastFloor(inMotion.minZ);
            int zmax = fastCeil(inMotion.maxZ);
            for (int x = xmin; x < xmax; x++) {
                for (int y = ymin; y < ymax; y++) {
                    for (int z = zmin; z < zmax; z++) {
                        if (!this.passable(x, y, z, context.ignoreLava)) {
                            return null;
                        }
                    }
                }
            }

            hitbox = hitbox.move(motion);
            displacement.add(displacement.get(displacement.size() - 1).add(motion));

            if (i >= ticksBoostDelay && remainingTicksBoosted-- > 0) {
                // See EntityFireworkRocket
                motion = motion.add(
                        lookDirection.x * 0.1 + (lookDirection.x * 1.5 - motion.x) * 0.5,
                        lookDirection.y * 0.1 + (lookDirection.y * 1.5 - motion.y) * 0.5,
                        lookDirection.z * 0.1 + (lookDirection.z * 1.5 - motion.z) * 0.5
                );
            }
        }

        return displacement;
    }

    private static Vec3 step(final Vec3 motion, final Vec3 lookDirection, final float pitch) {
        double motionX = motion.x;
        double motionY = motion.y;
        double motionZ = motion.z;

        float pitchRadians = pitch * RotationUtils.DEG_TO_RAD_F;
        double pitchBase2 = Math.sqrt(lookDirection.x * lookDirection.x + lookDirection.z * lookDirection.z);
        double flatMotion = Math.sqrt(motionX * motionX + motionZ * motionZ);
        double thisIsAlwaysOne = lookDirection.length();
        float pitchBase3 = Mth.cos(pitchRadians);
        //System.out.println("always the same lol " + -pitchBase + " " + pitchBase3);
        //System.out.println("always the same lol " + Math.abs(pitchBase3) + " " + pitchBase2);
        //System.out.println("always 1 lol " + thisIsAlwaysOne);
        pitchBase3 = (float) ((double) pitchBase3 * (double) pitchBase3 * Math.min(1, thisIsAlwaysOne / 0.4));
        motionY += -0.08 + (double) pitchBase3 * 0.06;
        if (motionY < 0 && pitchBase2 > 0) {
            double speedModifier = motionY * -0.1 * (double) pitchBase3;
            motionY += speedModifier;
            motionX += lookDirection.x * speedModifier / pitchBase2;
            motionZ += lookDirection.z * speedModifier / pitchBase2;
        }
        if (pitchRadians < 0) { // if you are looking down (below level)
            double anotherSpeedModifier = flatMotion * (double) (-Mth.sin(pitchRadians)) * 0.04;
            motionY += anotherSpeedModifier * 3.2;
            motionX -= lookDirection.x * anotherSpeedModifier / pitchBase2;
            motionZ -= lookDirection.z * anotherSpeedModifier / pitchBase2;
        }
        if (pitchBase2 > 0) { // this is always true unless you are looking literally straight up (let's just say the bot will never do that)
            motionX += (lookDirection.x / pitchBase2 * flatMotion - motionX) * 0.1;
            motionZ += (lookDirection.z / pitchBase2 * flatMotion - motionZ) * 0.1;
        }
        motionX *= 0.99f;
        motionY *= 0.98f;
        motionZ *= 0.99f;
        //System.out.println(motionX + " " + motionY + " " + motionZ);

        return new Vec3(motionX, motionY, motionZ);
    }

    private boolean passable(int x, int y, int z, boolean ignoreLava) {
        if (ignoreLava) {
            final BlockState state = this.bsi.get0(x, y, z);
            return state.getBlock() instanceof AirBlock || MovementHelper.isLava(state);
        } else {
            return !this.boi.get0(x, y, z);
        }
    }

    private void tickInventoryTransactions() {
        if (invTickCountdown <= 0) {
            Runnable r = invTransactionQueue.poll();
            if (r != null) {
                r.run();
                invTickCountdown = Baritone.settings().ticksBetweenInventoryMoves.value;
            }
        }
        if (invTickCountdown > 0) invTickCountdown--;
    }

    private void queueWindowClick(int windowId, int slotId, int button, ClickType type) {
        invTransactionQueue.add(() -> ctx.playerController().windowClick(windowId, slotId, button, type, ctx.player()));
    }

    private int findGoodElytra() {
        NonNullList<ItemStack> invy = ctx.player().getInventory().items;
        for (int i = 0; i < invy.size(); i++) {
            ItemStack slot = invy.get(i);
            if (slot.getItem() == Items.ELYTRA && (slot.getMaxDamage() - slot.getDamageValue()) > Baritone.settings().elytraMinimumDurability.value) {
                return i;
            }
        }
        return -1;
    }

    private void trySwapElytra() {
        // ★ 接管模式绝不换甲：甲飞靠的是「胸甲不是鞘翅也能滑翔」，
        //   一旦这里把真鞘翅换上去，甲飞+飞控就整条链路失效。
        if (!slimePhysics) {
            return;
        }
        if (!Baritone.settings().elytraAutoSwap.value || !invTransactionQueue.isEmpty()) {
            return;
        }

        ItemStack chest = ctx.player().getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA
                || chest.getMaxDamage() - chest.getDamageValue() > Baritone.settings().elytraMinimumDurability.value) {
            return;
        }

        int goodElytraSlot = findGoodElytra();
        if (goodElytraSlot != -1) {
            final int CHEST_SLOT = 6;
            final int slotId = goodElytraSlot < 9 ? goodElytraSlot + 36 : goodElytraSlot;
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ClickType.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, CHEST_SLOT, 0, ClickType.PICKUP);
            queueWindowClick(ctx.player().inventoryMenu.containerId, slotId, 0, ClickType.PICKUP);
        }
    }

    void logVerbose(String message) {
        if (Baritone.settings().elytraChatSpam.value) {
            logDebug(message);
        }
    }
}
