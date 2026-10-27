package com.example.sptrigger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Разработчик: selyavi
 * Описание: Ghost TriggerBot (Delta-Style Edition)
 */
public class SpTriggerClient implements ClientModInitializer {
    private static final double MISS_CHANCE = 0.15;
    private static final int BASE_HIT_DELAY = 2;
    private static final float SERVER_COOLDOWN = 0.92f;
    private static final double MAX_REACH = 3.3;
    private static final double FOV_DOT = 0.707;

    private static final float AIM_SPEED_1 = 0.30f;
    private static final float AIM_SPEED_2 = 0.28f;
    private static final float AIM_THRESHOLD = 15.0f;

    private boolean enabled = false;
    private boolean targetMobs = true;
    private int hitDelay = -1;
    private boolean missPlanned = false;
    private KeyMapping toggleKey;

    private final List<String> friends = new ArrayList<>();
    private Entity currentTarget = null;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.sptrg.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        HudRenderCallback.EVENT.register(this::renderHud);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("trigger")
                .then(ClientCommandManager.literal("on").executes(ctx -> {
                    enabled = true;
                    ctx.getSource().sendFeedback(Component.literal("§aGhost Trigger: ВКЛЮЧЕН"));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("off").executes(ctx -> {
                    enabled = false;
                    resetState();
                    ctx.getSource().sendFeedback(Component.literal("§cGhost Trigger: ВЫКЛЮЧЕН"));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    sendStatus(ctx.getSource());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("friend").then(ClientCommandManager.argument("name", StringArgumentType.string()).executes(ctx -> {
                    String name = StringArgumentType.getString(ctx, "name");
                    if (friends.contains(name)) {
                        friends.remove(name);
                        ctx.getSource().sendFeedback(Component.literal("§cУдален из друзей: " + name));
                    } else {
                        friends.add(name);
                        ctx.getSource().sendFeedback(Component.literal("§aДобавлен в друзья: " + name));
                    }
                    return Command.SINGLE_SUCCESS;
                })))
            );
        });
    }

    private void sendStatus(FabricClientCommandSource source) {
        String status = enabled ? "§aВКЛ" : "§cВЫКЛ";
        source.sendFeedback(Component.literal("§6=== Ghost Trigger Status ==="));
        source.sendFeedback(Component.literal("§fСтатус: " + status));
        source.sendFeedback(Component.literal("§fДрузей в списке: §b" + friends.size()));
    }

    private void resetState() {
        hitDelay = -1;
        missPlanned = false;
        currentTarget = null;
    }

    private Entity findTarget(Minecraft mc) {
        Entity bestTarget = null;
        double closestDist = MAX_REACH;
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = mc.player.getLookAngle();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !e.isAlive()) continue;

            if (e instanceof Player p && friends.contains(p.getName().getString())) continue;

            if (targetMobs) {
                if (!(e instanceof LivingEntity)) continue;
            } else {
                if (!(e instanceof Player)) continue;
            }

            double dist = mc.player.distanceTo(e);
            if (dist > MAX_REACH) continue;

            Vec3 targetPos = e.getBoundingBox().getCenter();
            Vec3 dirToTarget = targetPos.subtract(eyePos).normalize();
            double dot = lookVec.dot(dirToTarget);

            if (dot > FOV_DOT && dist < closestDist) {
                BlockHitResult hit = mc.level.clip(new ClipContext(eyePos, targetPos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                if (hit.getType() == HitResult.Type.BLOCK) continue;

                closestDist = dist;
                bestTarget = e;
            }
        }
        return bestTarget;
    }

    private void aimAt(Minecraft mc, Entity target) {
        Vec3 eyePos = mc.player.getEyePosition();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        double width = target.getBoundingBox().getXsize();
        double height = target.getBoundingBox().getYsize();
        double randomX = target.getX() + (rnd.nextDouble() * width);
        double randomY = target.getY() + (rnd.nextDouble() * height);
        double randomZ = target.getZ() + (rnd.nextDouble() * width);

        Vec3 targetVel = target.getDeltaMovement();
        double speed = targetVel.length();
        Vec3 targetPos = new Vec3(randomX, randomY, randomZ);
        if (speed > 0.01) {
             targetPos = targetPos.add(targetVel.scale(1.2));
        }

        Vec3 diff = targetPos.subtract(eyePos);
        float targetYaw = (float) (Math.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) -(Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)) * (180.0 / Math.PI));

        float yawDiff = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        float pitchDiff = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

        float currentSpeed = (Math.abs(yawDiff) > AIM_THRESHOLD || Math.abs(pitchDiff) > AIM_THRESHOLD) ? AIM_SPEED_1 : AIM_SPEED_2;

        if (missPlanned) {
            yawDiff += (float) ((rnd.nextDouble() - 0.5) * 6.0f);
            pitchDiff += (float) ((rnd.nextDouble() - 0.5) * 6.0f);
        }

        float newYaw = mc.player.getYRot() + yawDiff * currentSpeed;
        float newPitch = mc.player.getXRot() + pitchDiff * currentSpeed;

        if (rnd.nextFloat() < 0.3f) {
            newYaw += (rnd.nextFloat() - 0.5f) * 0.3f;
            newPitch += (rnd.nextFloat() - 0.5f) * 0.3f;
        }

        newPitch = Mth.clamp(newPitch, -90.0f, 90.0f);
        mc.player.setYRot(newYaw);
        mc.player.setXRot(newPitch);
    }

    private void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (!enabled) resetState();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("§6Ghost Trigger: " + (enabled ? "§aВКЛ" : "§cВЫКЛ")), false);
            }
        }

        if (!enabled || mc.player == null || mc.level == null || mc.screen != null) {
            resetState();
            return;
        }

        Entity target = findTarget(mc);
        currentTarget = target;

        if (target == null) {
            resetState();
            return;
        }

        aimAt(mc, target);

        int ping = 0;
        if (mc.getConnection() != null && mc.getConnection().getPlayerInfo(mc.player.getUUID()) != null) {
            ping = mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency();
        }
        int pingTicks = ping / 50;
        int actualDelay = BASE_HIT_DELAY + Math.min(pingTicks, 3);

        if (mc.player.getAttackStrengthScale(0.5f) < SERVER_COOLDOWN) {
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (hitDelay < 0) {
            hitDelay = rnd.nextInt(actualDelay + 1);
            missPlanned = rnd.nextDouble() < MISS_CHANCE;
        }

        if (hitDelay > 0) {
            hitDelay--;
            return;
        }

        boolean isFalling = !mc.player.onGround() && mc.player.getDeltaMovement().y < 0.0;
        boolean onGround = mc.player.onGround();

        if (isFalling || onGround) {
            if (missPlanned) {
                mc.player.swing(InteractionHand.MAIN_HAND);
                mc.player.resetAttackStrengthTicker();
            } else {
                if (mc.player.isSprinting()) {
                    mc.player.setSprinting(false);
                }
                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);
            }
            hitDelay = -1;
            missPlanned = false;
        }
    }

    // ИСПРАВЛЕНО: DeltaTracker вместо float
    private void renderHud(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || currentTarget == null || mc.player == null || mc.screen != null) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        String name = currentTarget.getName().getString();
        float health = 0;
        if (currentTarget instanceof LivingEntity living) {
            health = living.getHealth();
        }

        int x = screenWidth / 2 + 15;
        int y = screenHeight / 2 + 15;

        graphics.fill(x - 2, y - 2, x + 100, y + 25, 0x80000000);
        graphics.drawString(mc.font, "§cTarget: §f" + name, x, y, 0xFFFFFF);
        graphics.drawString(mc.font, "§cHP: §f" + String.format("%.1f", health), x, y + 12, 0xFFFFFF);
    }
}
