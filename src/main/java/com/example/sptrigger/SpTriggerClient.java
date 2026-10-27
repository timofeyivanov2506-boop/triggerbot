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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

public class SpTriggerClient implements ClientModInitializer {
    private static final double MISS_CHANCE = 0.10;
    private static final int BASE_HIT_DELAY = 2;
    private static final float SERVER_COOLDOWN = 0.85f; 
    private static final double MAX_REACH = 3.2; 
    
    private static final double AIM_FOV = 0.85; 
    private static final float AIM_SPEED = 0.35f; 

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
        source.sendFeedback(Component.literal("§fReach: §b" + MAX_REACH + " блоков"));
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
        Vec3 endPos = eyePos.add(lookVec.scale(MAX_REACH));

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

            AABB hitbox = e.getBoundingBox();
            boolean intersects = hitbox.clip(eyePos, endPos).isPresent();
            
            Vec3 targetPos = hitbox.getCenter();
            double dot = lookVec.dot(targetPos.subtract(eyePos).normalize());

            if (intersects || dot > AIM_FOV) {
                if (dist < closestDist) {
                    closestDist = dist;
                    bestTarget = e;
                }
            }
        }
        return bestTarget;
    }

    private void microAim(Minecraft mc, Entity target) {
        Vec3 eyePos = mc.player.getEyePosition();
        AABB hitbox = target.getBoundingBox();
        
        // Ближайшая точка хитбокса
        Vec3 closestPoint = new Vec3(
            Mth.clamp(eyePos.x, hitbox.minX, hitbox.maxX),
            Mth.clamp(eyePos.y, hitbox.minY, hitbox.maxY),
            Mth.clamp(eyePos.z, hitbox.minZ, hitbox.maxZ)
        );
        
        Vec3 targetVel = target.getDeltaMovement();
        Vec3 targetPos = closestPoint.add(targetVel.scale(2.0));

        Vec3 diff = targetPos.subtract(eyePos);
        float targetYaw = (float) (Math.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) -(Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)) * (180.0 / Math.PI));

        float yawDiff = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        float pitchDiff = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

        // Плавный доворот (интерполяция)
        float newYaw = mc.player.getYRot() + yawDiff * AIM_SPEED;
        float newPitch = mc.player.getXRot() + pitchDiff * AIM_SPEED;

        // Дрожание
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextFloat() < 0.2f) {
            newYaw += (rnd.nextFloat() - 0.5f) * 0.2f;
            newPitch += (rnd.nextFloat() - 0.5f) * 0.2f;
        }

        newPitch = Mth.clamp(newPitch, -90.0f, 90.0f);
        
        // ВАЖНО: Устанавливаем углы плавно, а не резко
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

        microAim(mc, target);

        if (mc.player.getAttackStrengthScale(0.5f) < SERVER_COOLDOWN) {
            return;
        }

        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = mc.player.getLookAngle();
        Vec3 endPos = eyePos.add(lookVec.scale(MAX_REACH));
        AABB hitbox = target.getBoundingBox();
        
        if (!hitbox.clip(eyePos, endPos).isPresent()) {
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int ping = 0;
        if (mc.getConnection() != null && mc.getConnection().getPlayerInfo(mc.player.getUUID()) != null) {
            ping = mc.getConnection().getPlayerInfo(mc.player.getUUID()).getLatency();
        }
        int actualDelay = BASE_HIT_DELAY + Math.min(ping / 50, 3);

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
