package com.example.sptrigger;

import com.mojang.brigadier.Command;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.IntegratedServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Тестовый триггербот. РАБОТАЕТ ТОЛЬКО В ОДИНОЧНОМ МИРЕ (встроенный сервер, один игрок, без LAN).
 * Управление: клиентская команда /sptrigger (вкл/выкл), /sptrigger mobs (цели: игроки <-> любые живые).
 */
public class SpTriggerClient implements ClientModInitializer {
    /** Шанс намеренного промаха на каждую возможность удара. */
    private static final double MISS_CHANCE = 0.07;
    /** Случайная задержка перед ударом (в тиках) после готовности. */
    private static final int MAX_HIT_DELAY = 2;
    /** Случайная пауза после приземления перед новым прыжком (в тиках). */
    private static final int MAX_JUMP_DELAY = 3;

    private boolean enabled = false;
    private boolean targetMobs = false;

    private boolean jumpHeld = false;
    private int jumpDelay = -1;
    private int hitDelay = -1;
    private boolean missPlanned = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("sptrigger")
                .executes(ctx -> {
                    enabled = !enabled;
                    ctx.getSource().sendFeedback(Component.literal(
                        "SP Trigger: " + (enabled ? "ВКЛ" : "ВЫКЛ") + " (только одиночный мир)"));
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommandManager.literal("mobs").executes(ctx -> {
                    targetMobs = !targetMobs;
                    ctx.getSource().sendFeedback(Component.literal(
                        "SP Trigger: цели = " + (targetMobs ? "любые живые существа" : "только игроки")));
                    return Command.SINGLE_SUCCESS;
                }))));
    }

    /** Жёсткая блокировка: только встроенный сервер, ровно один игрок, LAN закрыт. */
    private static boolean singleplayerOnly(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        return server != null && !server.isPublished() && server.getPlayerCount() <= 1;
    }

    private void releaseJump(Minecraft mc) {
        if (jumpHeld) {
            mc.options.keyJump.setDown(false);
            jumpHeld = false;
        }
    }

    private void resetState() {
        jumpDelay = -1;
        hitDelay = -1;
        missPlanned = false;
    }

    private Entity aimedTarget(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult ehr)) {
            return null;
        }
        Entity e = ehr.getEntity();
        if (e == mc.player || !e.isAlive()) {
            return null;
        }
        if (targetMobs) {
            return e instanceof LivingEntity ? e : null;
        }
        return e instanceof Player ? e : null;
    }

    private static boolean canCrit(LocalPlayer p) {
        return p.fallDistance > 0
            && !p.onGround()
            && !p.onClimbable()
            && !p.isInWater()
            && !p.hasEffect(MobEffects.BLINDNESS)
            && !p.isPassenger();
    }

    private void tick(Minecraft mc) {
        // Отпускаем прыжок, нажатый в прошлом тике.
        releaseJump(mc);

        LocalPlayer player = mc.player;
        if (!enabled || player == null || mc.level == null || mc.gameMode == null
            || mc.screen != null || !singleplayerOnly(mc)) {
            resetState();
            return;
        }

        Entity target = aimedTarget(mc);
        if (target == null) {
            resetState();
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (player.onGround()) {
            hitDelay = -1;
            if (player.isInWater() || player.isPassenger()) {
                return;
            }
            if (jumpDelay < 0) {
                jumpDelay = rnd.nextInt(MAX_JUMP_DELAY + 1);
            }
            if (jumpDelay > 0) {
                jumpDelay--;
                return;
            }
            mc.options.keyJump.setDown(true);
            jumpHeld = true;
            jumpDelay = -1;
            return;
        }

        // В воздухе: бьём только при полном КД и когда удар будет критическим (падение).
        jumpDelay = -1;
        if (!canCrit(player) || player.getAttackStrengthScale(0.5F) < 1.0F) {
            return;
        }

        if (hitDelay < 0) {
            hitDelay = rnd.nextInt(MAX_HIT_DELAY + 1);
            missPlanned = rnd.nextDouble() < MISS_CHANCE;
        }
        if (hitDelay > 0) {
            hitDelay--;
            return;
        }

        if (missPlanned) {
            // Промах как в ваниле: взмах в пустоту + сброс КД.
            player.swing(InteractionHand.MAIN_HAND);
            player.resetAttackStrengthTicker();
        } else {
            // Крит в ваниле невозможен во время спринта.
            if (player.isSprinting()) {
                player.setSprinting(false);
            }
            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
        hitDelay = -1;
        missPlanned = false;
    }
}
