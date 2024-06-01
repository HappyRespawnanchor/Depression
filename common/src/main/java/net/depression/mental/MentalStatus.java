package net.depression.mental;

import net.depression.network.ActionbarHintPacket;
import net.depression.network.MentalStatusPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.apache.logging.log4j.core.jmx.Server;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.depression.mental.MentalIllness.getMentalHealthLevel;

public class MentalStatus {
    public static double EMOTION_STABILIZE_RATE;
    public static double MENTAL_HEALTH_CHANGE_RATE;
    public static double PTSD_DAMAGE_RATE;
    public static double PTSD_DISPERSE_RATE;

    public static double FOOD_HEAL_RATE;

    public static int BOREDOM_DECREASE_TICK;

    public static HashMap<String, Double> nearbyHealBlockValue = new HashMap<>(); //精神治疗光环方块-治疗值
    public static HashMap<String, Integer> nearbyHealBlockRadius = new HashMap<>(); //精神治疗光环方块-作用半径

    public static int radiusMaxValue; //精神治疗光环方块-最大的半径

    public static HashMap<String, Double> breakHealBlock = new HashMap<>(); //挖了会开心的方块
    public static HashMap<String, Double> killHealEntity = new HashMap<>(); //杀了会开心的实体

    public static HashMap<String, Double> smeltHealItem = new HashMap<>(); //熔炼了会开心的物品

    private ConcurrentHashMap<String, Integer> boredom = new ConcurrentHashMap<>(); //无聊值
    private ConcurrentHashMap<String, Double> PTSD = new ConcurrentHashMap<>(); //PTSD值
    private ConcurrentHashMap<String, Long> PTSDTimeBuffer = new ConcurrentHashMap<>(); //PTSD时刻缓冲区（存储造成PTSD的那一个tick）
    private ConcurrentHashMap<String, Double> PTSDValueBuffer = new ConcurrentHashMap<>(); //PTSD值缓冲区（存储造成PTSD的值）
    public double emotionValue; //情绪值（-20~20），实际上是精神健康值的导数。
    public double mentalHealthValue = 100; //精神健康值（0~100）
    public MentalIllness mentalIllness;

    private ServerPlayer player;
    public long tickCount = -1;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private AttributeModifier emotionModifier;

    public MentalStatus(ServerPlayer player) {
        this.player = player;
        this.mentalIllness = new MentalIllness(player, this);
    }

    public synchronized void tick() {
        ++tickCount;
        //处理无聊值
        if (tickCount % BOREDOM_DECREASE_TICK == 0) {
            for (Map.Entry<String, Integer> entry : boredom.entrySet()) {
                entry.setValue(entry.getValue() - 1);
                if (entry.getValue() <= 1) { //若无聊值归零，则移除其无聊计数
                    boredom.remove(entry.getKey());
                }
            }
        }
        //处理PTSD缓冲区
        for (Map.Entry<String, Long> entry : PTSDTimeBuffer.entrySet()) {
            if (tickCount - entry.getValue() >= 200L) { //PTSD缓冲区持续200tick后，正式形成PTSD
                String string = entry.getKey();
                Double damage = PTSDValueBuffer.get(string);
                if (damage == null) {
                    PTSDTimeBuffer.remove(string);
                    continue;
                }
                Double originValue = PTSD.get(string);
                if (originValue == null) {
                    originValue = 0d;
                    ActionbarHintPacket.sendPTSDFormPacket(player, string);
                }
                PTSD.put(string, Math.min(originValue + damage, 10d)); //保证PTSD不超过上限
                PTSDTimeBuffer.remove(string);
                PTSDValueBuffer.remove(string);
            }
        }
        if (tickCount % 20L == 0L) {
            //方块光环检测
            executor.submit(this::detectNearbyHealBlock);
            //睡眠检测
            if (player.isSleeping()) {
                if (mentalIllness.isInsomnia == null) {
                    mentalIllness.setIsInsomnia();
                }
                if (!mentalIllness.isInsomnia) {
                    mentalHeal("sleeping", 1d);
                }
            }
            //PTSD的自然消散
            for (Map.Entry<String, Double> entry : PTSD.entrySet()) {
                entry.setValue(entry.getValue() - PTSD_DISPERSE_RATE); //PTSD值 -= 自然消散速度
                if (entry.getValue() <= 0) { //如果PTSD值归零，则移除PTSD
                    String id = entry.getKey();
                    PTSD.remove(id);
                    ActionbarHintPacket.sendPTSDDispersePacket(player, id);
                }
            }
            //处理精神健康值
            mentalHealthValue += emotionValue * MENTAL_HEALTH_CHANGE_RATE;
            mentalHealthValue = Math.max(0d, mentalHealthValue); //保证精神健康值不超过下限
            mentalHealthValue = Math.min(100d, mentalHealthValue); //保证精神健康值不超过上限

            //情绪值自然归零（速度 0.1/s)
            if (emotionValue < 0) {
                emotionValue += EMOTION_STABILIZE_RATE * Math.abs(emotionValue) / 20d;
                emotionValue = Math.min(0d, emotionValue); //保证情绪值归0
            }
            else {
                emotionValue -= EMOTION_STABILIZE_RATE * Math.abs(emotionValue) / 20d;
                emotionValue = Math.max(0d, emotionValue); //保证情绪值归0
            }

            //更新属性
            AttributeInstance movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
            AttributeInstance attackDamage = player.getAttribute(Attributes.ATTACK_DAMAGE);
            AttributeInstance attackSpeed = player.getAttribute(Attributes.ATTACK_SPEED);
            if (emotionModifier != null) {
                movementSpeed.removeModifier(emotionModifier);
                attackDamage.removeModifier(emotionModifier);
                attackSpeed.removeModifier(emotionModifier);
            }
            emotionModifier = new AttributeModifier("depression:emotion_modifier", emotionValue / 100d, AttributeModifier.Operation.MULTIPLY_TOTAL);
            movementSpeed.addTransientModifier(emotionModifier);
            attackDamage.addTransientModifier(emotionModifier);
            attackSpeed.addTransientModifier(emotionModifier);
            MentalStatusPacket.sendToPlayer(player, this);
        }
        mentalIllness.tick();
    }

    public synchronized void mentalHeal(double value) {
        emotionValue += value * Math.max(mentalHealthValue, 10d) / 100d; //情绪值 += 治疗值 * 精神健康值 / 100
        emotionValue = Math.min(20d, emotionValue); //保证情绪值不超过上限
    }

    public synchronized void mentalHeal(String string, double value) {
        if (boredom.containsKey(string)) {
            boredom.put(string, boredom.get(string) + 1);
        }
        else {
            boredom.put(string, 2);
        }
        value /= boredom.get(string) / 2d; //治疗值 /= 无聊值
        mentalHeal(value);
    }

    public synchronized void mentalHurt(double value) {
        emotionValue -= value; //情绪值 -= 伤害值
        emotionValue = Math.max(-20d, emotionValue); //保证情绪值不超过下限
    }
    public synchronized void mentalHurt(String string, double damage) {
        Level level = player.level();
        BlockPos pos = player.blockPosition();
        int brightness = Math.max(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
        if (brightness <= 7) {
            damage *= 1.3d + (7d - brightness) / 7d * 0.2d;
        }
        if (PTSDTimeBuffer.containsKey(string)) { //如果缓冲区中已经存在PTSD，则更新PTSD缓冲值
            Double originValue = PTSDValueBuffer.get(string);
            if (originValue == null) {
                originValue = 0d;
            }
            PTSDTimeBuffer.put(string, tickCount); //更新PTSD时刻缓冲区
            PTSDValueBuffer.put(string, originValue + damage); //更新PTSD值缓冲区
        }
        else {
            Double PTSDValue = PTSD.get(string);
            if (PTSDValue != null) { //如果此前有过PTSD且缓冲区没有，则计算为犯了PTSD，PTSD影响情绪
                emotionValue -= PTSDValue * PTSD_DAMAGE_RATE; //情绪值 -= PTSD值原量 * 0.5
                emotionValue = Math.max(-20d, emotionValue); //保证情绪值不超过下限
            }
            PTSDTimeBuffer.put(string, tickCount); //加入PTSD时刻缓冲区
            PTSDValueBuffer.put(string, damage); //加入PTSD值缓冲区
        }
        emotionValue -= damage;
        emotionValue = Math.max(-20d, emotionValue); //保证情绪值不超过下限
    }


    public void detectNearbyHealBlock() { //检测周围球形半径的治疗光环方块
        HashSet<String> detected = new HashSet<>();
        BlockPos pos = player.blockPosition();
        Level level = player.level();
        int r = radiusMaxValue;
        for (int x = -r; x <= r; ++x) { // -r <= x <= r
            int yLimit = (int) Math.sqrt(r*r - x*x);
            for (int y = -yLimit; y <= yLimit; ++y) { // -sqrt(r*r - x*x) <= y <= sqrt(r*r - x*x)
                int zLimit = (int) Math.sqrt(r*r - x*x - y*y);
                for (int z = -zLimit; z <= zLimit; ++z) { // -sqrt(r*r - x*x - y*y) <= z <= sqrt(r*r - x*x - y*y)
                    BlockPos blockPos = pos.offset(x, y, z);
                    if (level.getChunk(blockPos).getStatus().isOrAfter(ChunkStatus.FULL)) { //若该区块已加载才进行计算
                        Block block = level.getBlockState(blockPos).getBlock();
                        if (block instanceof FlowerPotBlock) {
                            block = ((FlowerPotBlock) block).getContent();
                        }
                        String id = block.arch$registryName().toString();
                        if (detected.contains(id)) {
                            continue;
                        }
                        Double value = nearbyHealBlockValue.get(id);
                        Integer radius = nearbyHealBlockRadius.get(id);
                        if (radius != null && value != null) {
                            int distance = (int) Math.sqrt(x*x + y*y + z*z);
                            if (distance <= radius) {
                                mentalHeal(id, value);
                                detected.add(id);
                            }
                        }
                    }
                }
            }
        }
    }

    public void readNbt(CompoundTag tag) {
        //读取无聊值
        CompoundTag boredomTag = tag.getCompound("boredom");
        for (String key : boredomTag.getAllKeys()) {
            boredom.put(key, boredomTag.getInt(key));
        }
        //读取PTSD
        CompoundTag ptsd = tag.getCompound("PTSD");
        for (String key : ptsd.getAllKeys()) {
            PTSD.put(key, ptsd.getDouble(key));
        }
        //读取PTSD时刻缓冲区
        CompoundTag ptsdTimeBuffer = tag.getCompound("PTSD_time_buffer");
        for (String key : ptsdTimeBuffer.getAllKeys()) {
            PTSDTimeBuffer.put(key, tickCount - ptsdTimeBuffer.getLong(key)); //读取造成PTSD的时刻而不是时间
        }
        //读取PTSD值缓冲区
        CompoundTag ptsdValueBuffer = tag.getCompound("PTSD_value_buffer");
        for (String key : ptsdValueBuffer.getAllKeys()) {
            PTSDValueBuffer.put(key, ptsdValueBuffer.getDouble(key));
        }
        //读取情绪值
        emotionValue = tag.getDouble("emotion_value");
        //读取精神健康值
        mentalHealthValue = tag.getDouble("mental_health_value");
        //读取精神疾病
        mentalIllness.readNbt(tag);
    }

    public void writeNbt(CompoundTag tag) {
        //写入无聊值
        CompoundTag boredomTag = new CompoundTag();
        for (Map.Entry<String, Integer> entry : boredom.entrySet()) {
            boredomTag.putInt(entry.getKey(), entry.getValue());
        }
        tag.put("boredom", boredomTag);
        //写入PTSD
        CompoundTag ptsd = new CompoundTag();
        for (Map.Entry<String, Double> entry : PTSD.entrySet()) {
            ptsd.putDouble(entry.getKey(), entry.getValue());
        }
        tag.put("PTSD", ptsd);
        //写入PTSD时刻缓冲区
        CompoundTag ptsdTimeBuffer = new CompoundTag();
        for (Map.Entry<String, Long> entry : PTSDTimeBuffer.entrySet()) {
            ptsdTimeBuffer.putLong(entry.getKey(), tickCount - entry.getValue()); //存储造成PTSD的时间而不是时刻
        }
        tag.put("PTSD_time_buffer", ptsdTimeBuffer);
        //写入PTSD值缓冲区
        CompoundTag ptsdValueBuffer = new CompoundTag();
        for (Map.Entry<String, Double> entry : PTSDValueBuffer.entrySet()) {
            ptsdValueBuffer.putDouble(entry.getKey(), entry.getValue());
        }
        //写入情绪值
        tag.putDouble("emotion_value", emotionValue);
        //写入精神健康值
        tag.putDouble("mental_health_value", mentalHealthValue);
        //写入精神疾病
        mentalIllness.writeNbt(tag);
    }

}
