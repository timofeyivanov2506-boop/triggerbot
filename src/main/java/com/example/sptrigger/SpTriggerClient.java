package com.example.sptrigger;

import com.mojang.brigadier.Command;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft; // ИСПРАВЛЕНО: было MinecraftClient
import net.minecraft.client.player.LocalPlayer; // ИСПРАВЛЕНО: было ClientPlayerEntity
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public class SpTriggerClient implements ClientModInitializer {
    /** Шанс намеренного промаха на каждую возможность удара. */
    private static final double MISS_CHANCE = 0.01; 
    /** Случайная задержка перед ударом (в тиках) после готовности. */
    private static final int MAX_HIT_DELAY = 2;

    private boolean enabled = false;
    private boolean targetMobs = false;

    private int hitDelay = -1;
    private boolean missPlanned = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("sptrg")
                .executes(ctx -> {
                    enabled = !enabled;
                    ctx.getSource().sendFeedback(Component.literal(
                        "SP Trigger: " + (enabled ? "ВКЛ" : "ВЫКЛ"))); 
                    return Command.SINGLE_SUCCESS;
                })
                .then(ClientCommandManager.literal("mobs").executes(ctx -> {
                    targetMobs = !targetMobs;
                    ctx.getSource().sendFeedback(Component.literal(
                        "SP Trigger: цель = " + (targetMobs ? "любые живые существа" : "только игроки")));
                    return Command.SINGLE_SUCCESS;
                }))));
    }

    private void resetState() {
        hitDelay = -1;
        missPlanned = false;
    }

    private Entity aimedTarget(Minecraft mc) { // ИСПРАВЛЕНО: Minecraft
        // Проверяем, куда смотрит игрок
        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult ehr)) {
            return null;
        }
        Entity e = ehr.getEntity();
        
        // Не бьем себя и мертвых
        if (e == mc.player || !e.isAlive()) {
            return null;
        }
        
        // Фильтр по мобам/игрокам
        if (targetMobs) {
            return e instanceof LivingEntity ? e : null;
        }
        return e instanceof Player ? e : null;
    }

    private void tick(Minecraft mc) { // ИСПРАВЛЕНО: Minecraft
        // Если игрок не в игре или мод выключен - сбрасываем состояние
        if (!enabled || mc.player == null || mc.level == null || mc.screen != null) {
            resetState();
            return;
        }

        // Ищем цель
        Entity target = aimedTarget(mc);
        if (target == null) {
            resetState();
            return;
        }

        // ПРОВЕРКА ДИСТАНЦИИ: не бьем, если цель слишком далеко (больше 3.2 блоков)
        if (mc.player.distanceTo(target) > 3.2f) {
            return;
        }

        // ПРОВЕРКА КУЛДАУНА: ждем, пока шкала атаки заполнится хотя бы на 90%
        if (mc.player.getAttackStrengthScale(0.5f) < 0.9f) {
            return;
        }

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        // ЛОГИКА ЗАДЕРЖКИ ПЕРЕД УДАРОМ
        if (hitDelay < 0) {
            // Если задержки нет, генерируем новую (от 1 до MAX_HIT_DELAY тиков)
            hitDelay = rnd.nextInt(MAX_HIT_DELAY + 1);
            missPlanned = rnd.nextDouble() < MISS_CHANCE; // Решаем, будет ли промах
        }

        if (hitDelay > 0) {
            hitDelay--;
            return; // Ждем окончания задержки
        }

        // ЛОГИКА УДАРА
        if (missPlanned) {
            // Промах: машем рукой в пустоту
            mc.player.swing(InteractionHand.MAIN_HAND);
            mc.player.resetAttackStrengthTicker();
        } else {
            // Удар: сбрасываем спринт для крита, если нужно
            if (mc.player.isSprinting()) {
                mc.player.setSprinting(false);
            }
            // БЬЕМ! (В Mojmap это gameMode, а не interactionManager)
            mc.gameMode.attack(mc.player, target);
            mc.player.swing(InteractionHand.MAIN_HAND);
        }

        // Сбрасываем таймеры после удара
        hitDelay = -1;
        missPlanned = false;
    }
}
