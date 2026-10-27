package com.example.sptrigger;

import com.mojang.brigadier.Command;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

public class SpTriggerClient implements ClientModInitializer {
    private static final double MISS_CHANCE = 0.01;
    private static final int MAX_HIT_DELAY = 2;
    private static final double MAX_REACH = 3.5;
    // Чем меньше значение, тем плавнее наводка (0.15 = очень плавно, 0.5 = резко)
    private static final float AIM_SPEED = 0.15f;
    
    private boolean enabled = false;
    private boolean targetMobs = true;

    private int hitDelay = -1;
    private boolean missPlanned = false;
    private boolean jumpHeld = false;
    private KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        // Регистрируем клавишу R для включения/выключения
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.sptrg.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R, // Кнопка R
            "SP Trigger"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("trigger")
                .then(ClientCommandManager.literal("on").executes(ctx -> {
                    enabled = true;
                    ctx.getSource().sendFeedback(Component.literal("§aSP Trigger: ВКЛЮЧЕН"));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("off").executes(ctx -> {
                    enabled = false;
                    resetState();
                    ctx.getSource().sendFeedback(Component.literal("§cSP Trigger: ВЫКЛЮЧЕН"));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    sendStatus(ctx.getSource());
                    return Command.SINGLE_SUCCESS;
                }))
            );
        });
    }

    private void sendStatus(FabricClientCommandSource source) {
        String status = enabled ? "§aВКЛ" : "§cВЫКЛ";
        String mode = targetMobs ? "§bМобы + Игроки" : "§bТолько Игроки";
        
        source.sendFeedback(Component.literal("§6=== SP Trigger Status ==="));
        source.sendFeedback(Component.literal("§fСтатус: " + status));
        source.sendFeedback(Component.literal("§fЦели: " + mode));
        source.sendFeedback(Component.literal("§fРадиус: " + MAX_REACH + " блоков"));
    }

    private void resetState() {
        hitDelay = -1;
        missPlanned = false;
        if (jumpHeld) {
            Minecraft.getInstance().options.keyJump.setDown(false);
            jumpHeld = false;
        }
    }

    private Entity findTarget(Minecraft mc) {
        Entity bestTarget = null;
        double closestDist = MAX_REACH;
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 lookVec = mc.player.getLookAngle();

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || !e.isAlive()) continue;
            
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

            // Если цель в поле зрения (dot > 0.5) и ближе всех остальных - берем ее
            if (dot > 0.5 && dist < closestDist) {
                closestDist = dist;
                bestTarget = e;
            }
        }
        return bestTarget;
    }

    private void aimAt(Minecraft mc, Entity target) {
        Vec3 eyePos = mc.player.getEyePosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        Vec3 diff = targetPos.subtract(eyePos);

        float targetYaw = (float) (Math.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float targetPitch = (float) -(Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)) * (180.0 / Math.PI));

        float yawDiff = Mth.wrapDegrees(targetYaw - mc.player.getYRot());
        float pitchDiff = Mth.wrapDegrees(targetPitch - mc.player.getXRot());

        // Плавный доворот
        float newYaw = mc.player.getYRot() + yawDiff * AIM_SPEED;
        float newPitch = mc.player.getXRot() + pitchDiff * AIM_SPEED;

        newPitch = Mth.clamp(newPitch, -90.0f, 90.0f);

        mc.player.setYRot(newYaw);
        mc.player.setXRot(newPitch);
    }

    private void tick(Minecraft mc) {
        // Проверка нажатия клавиши R
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            if (!enabled) resetState();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§6SP Trigger: " + (enabled ? "§aВКЛ" : "§cВЫКЛ")));
            }
        }

        if (!enabled || mc.player == null || mc.level == null || mc.screen != null) {
            resetState();
            return;
        }

        Entity target = findTarget(mc);
        if (target == null) {
            resetState();
            return;
        }

        aimAt(mc, target);

        // Проверка кулдауна (90% готовности)
        if (mc.player.getAttackStrengthScale(0.5f) < 0.9f) {
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (hitDelay < 0) {
            hitDelay = rnd.nextInt(MAX_HIT_DELAY + 1);
            missPlanned = rnd.nextDouble() < MISS_CHANCE;
        }

        if (hitDelay > 0) {
            hitDelay--;
            return;
        }

        // ЛОГИКА ПРЫЖКА
        if (mc.player.onGround() && !jumpHeld) {
            mc.options.keyJump.setDown(true);
            jumpHeld = true;
        } 
        // ЛОГИКА УДАРА (Бьем, когда падаем, для крита)
        else if (!mc.player.onGround() && mc.player.getDeltaMovement().y < 0.0) {
            if (missPlanned) {
                mc.player.swing(InteractionHand.MAIN_HAND);
                mc.player.resetAttackStrengthTicker();
            } else {
                if (mc.player.isSprinting()) {
                    mc.player.setSprinting(false); // Сброс спринта для крита
                }
                
                // Сам удар
                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);
                
                // Отпускаем прыжок, чтобы не улететь в космос
                if (jumpHeld) {
                    mc.options.keyJump.setDown(false);
                    jumpHeld = false;
                }
            }
            hitDelay = -1;
            missPlanned = false;
        }
    }
}
