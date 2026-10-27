package com.example.sptrigger;

import com.mojang.blaze3d.platform.InputConstants;
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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Разработчик: selyavi
 * Описание: Бесплатный Ghost TriggerBot с открытым исходным кодом.
 */
public class SpTriggerClient implements ClientModInitializer {
    // --- Настройки Ghost-режима ---
    private static final double MISS_CHANCE = 0.15;      // 15% шанс промаха
    private static final int MAX_HIT_DELAY = 4;          // Задержка между ударами (тики)
    private static final double MAX_REACH = 3.3;         // Чуть меньше ванильного радиуса (3.0), чтобы не палить
    private static final float AIM_SPEED = 0.08f;        // ОЧЕНЬ плавная наводка
    private static final float SHAKE_INTENSITY = 0.4f;   // Сила дрожания мыши
    
    // Задержка реакции перед первым ударом (в тиках: 3-6 = 150-300мс)
    private static final int MIN_REACTION = 3;
    private static final int MAX_REACTION = 6;

    private boolean enabled = false;
    private boolean targetMobs = true;

    private int hitDelay = -1;
    private int reactionTimer = -1;
    private boolean missPlanned = false;
    private boolean jumpHeld = false;
    private KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.sptrg.toggle",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            KeyMapping.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

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
            );
        });
    }

    private void sendStatus(FabricClientCommandSource source) {
        String status = enabled ? "§aВКЛ" : "§cВЫКЛ";
        String mode = targetMobs ? "§bМобы + Игроки" : "§bТолько Игроки";

        source.sendFeedback(Component.literal("§6=== Ghost Trigger Status ==="));
        source.sendFeedback(Component.literal("§fСтатус: " + status));
        source.sendFeedback(Component.literal("§fЦели: " + mode));
        source.sendFeedback(Component.literal("§fРадиус: " + MAX_REACH + " блоков"));
        source.sendFeedback(Component.literal("§fGhost Mode: §aВКЛ (Рандом + Дрожание)"));
    }

    private void resetState() {
        hitDelay = -1;
        reactionTimer = -1;
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

        // ДРОЖАНИЕ МЫШИ (имитация руки человека)
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        if (rnd.nextFloat() < 0.3f) { // Дрожит не каждый тик, а иногда
            newYaw += (rnd.nextFloat() - 0.5f) * SHAKE_INTENSITY;
            newPitch += (rnd.nextFloat() - 0.5f) * SHAKE_INTENSITY;
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
        if (target == null) {
            resetState();
            return;
        }

        // Человеческая реакция перед первым ударом
        if (reactionTimer < 0) {
            reactionTimer = ThreadLocalRandom.current().nextInt(MIN_REACTION, MAX_REACTION + 1);
            resetState(); // Сбрасываем хит-таймер, чтобы не ударить раньше времени
        }

        if (reactionTimer > 0) {
            reactionTimer--;
            aimAt(mc, target); // Пока "думаем", наводимся
            return;
        }

        aimAt(mc, target);

        if (mc.player.getAttackStrengthScale(0.5f) < 0.9f) {
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (hitDelay < 0) {
            // Рандомная задержка между ударами
            hitDelay = rnd.nextInt(MAX_HIT_DELAY + 1);
            missPlanned = rnd.nextDouble() < MISS_CHANCE;
        }

        if (hitDelay > 0) {
            hitDelay--;
            return;
        }

        // ЛОГИКА ПРЫЖКА (с шансом на "лак"/ошибку тайминга)
        if (mc.player.onGround() && !jumpHeld) {
            // 10% шанс, что бот "проспит" момент и прыгнет чуть позже
            if (rnd.nextFloat() < 0.1f && hitDelay != -1) return; 
            
            mc.options.keyJump.setDown(true);
            jumpHeld = true;
        }
        else if (!mc.player.onGround() && mc.player.getDeltaMovement().y < 0.0) {
            if (missPlanned) {
                mc.player.swing(InteractionHand.MAIN_HAND);
                mc.player.resetAttackStrengthTicker();
            } else {
                if (mc.player.isSprinting()) {
                    mc.player.setSprinting(false);
                }

                mc.gameMode.attack(mc.player, target);
                mc.player.swing(InteractionHand.MAIN_HAND);

                if (jumpHeld) {
                    mc.options.keyJump.setDown(false);
                    jumpHeld = false;
                }
            }
            hitDelay = -1;
            missPlanned = false;
            reactionTimer = -1; // Сбрасываем реакцию
        }
    }
}
