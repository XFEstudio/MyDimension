package com.xfestudio.mydimension.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.timers.TimerQueue;

import javax.annotation.Nullable;
import java.util.UUID;

/** Delegates normal level metadata while replacing only the shared daylight clock. */
final class MindServerLevelData implements ServerLevelData {
    private final ServerLevelData delegate;
    private final MindTimeSavedData time;

    MindServerLevelData(ServerLevelData delegate, MindTimeSavedData time) {
        this.delegate = delegate;
        this.time = time;
    }

    @Override
    public int getXSpawn() {
        return delegate.getXSpawn();
    }

    @Override
    public int getYSpawn() {
        return delegate.getYSpawn();
    }

    @Override
    public int getZSpawn() {
        return delegate.getZSpawn();
    }

    @Override
    public float getSpawnAngle() {
        return delegate.getSpawnAngle();
    }

    @Override
    public long getGameTime() {
        return delegate.getGameTime();
    }

    @Override
    public long getDayTime() {
        return time.dayTime();
    }

    @Override
    public boolean isThundering() {
        return delegate.isThundering();
    }

    @Override
    public boolean isRaining() {
        return delegate.isRaining();
    }

    @Override
    public void setRaining(boolean raining) {
        delegate.setRaining(raining);
    }

    @Override
    public boolean isHardcore() {
        return delegate.isHardcore();
    }

    @Override
    public GameRules getGameRules() {
        return delegate.getGameRules();
    }

    @Override
    public Difficulty getDifficulty() {
        return delegate.getDifficulty();
    }

    @Override
    public boolean isDifficultyLocked() {
        return delegate.isDifficultyLocked();
    }

    @Override
    public void setXSpawn(int x) {
        delegate.setXSpawn(x);
    }

    @Override
    public void setYSpawn(int y) {
        delegate.setYSpawn(y);
    }

    @Override
    public void setZSpawn(int z) {
        delegate.setZSpawn(z);
    }

    @Override
    public void setSpawnAngle(float angle) {
        delegate.setSpawnAngle(angle);
    }

    @Override
    public void setSpawn(BlockPos position, float angle) {
        delegate.setSpawn(position, angle);
    }

    @Override
    public String getLevelName() {
        return delegate.getLevelName();
    }

    @Override
    public void setThundering(boolean thundering) {
        delegate.setThundering(thundering);
    }

    @Override
    public int getRainTime() {
        return delegate.getRainTime();
    }

    @Override
    public void setRainTime(int rainTime) {
        delegate.setRainTime(rainTime);
    }

    @Override
    public void setThunderTime(int thunderTime) {
        delegate.setThunderTime(thunderTime);
    }

    @Override
    public int getThunderTime() {
        return delegate.getThunderTime();
    }

    @Override
    public int getClearWeatherTime() {
        return delegate.getClearWeatherTime();
    }

    @Override
    public void setClearWeatherTime(int clearWeatherTime) {
        delegate.setClearWeatherTime(clearWeatherTime);
    }

    @Override
    public int getWanderingTraderSpawnDelay() {
        return delegate.getWanderingTraderSpawnDelay();
    }

    @Override
    public void setWanderingTraderSpawnDelay(int delay) {
        delegate.setWanderingTraderSpawnDelay(delay);
    }

    @Override
    public int getWanderingTraderSpawnChance() {
        return delegate.getWanderingTraderSpawnChance();
    }

    @Override
    public void setWanderingTraderSpawnChance(int chance) {
        delegate.setWanderingTraderSpawnChance(chance);
    }

    @Nullable
    @Override
    public UUID getWanderingTraderId() {
        return delegate.getWanderingTraderId();
    }

    @Override
    public void setWanderingTraderId(UUID id) {
        delegate.setWanderingTraderId(id);
    }

    @Override
    public GameType getGameType() {
        return delegate.getGameType();
    }

    @Override
    public void setWorldBorder(WorldBorder.Settings settings) {
        delegate.setWorldBorder(settings);
    }

    @Override
    public WorldBorder.Settings getWorldBorder() {
        return delegate.getWorldBorder();
    }

    @Override
    public boolean isInitialized() {
        return delegate.isInitialized();
    }

    @Override
    public void setInitialized(boolean initialized) {
        delegate.setInitialized(initialized);
    }

    @Override
    public boolean getAllowCommands() {
        return delegate.getAllowCommands();
    }

    @Override
    public void setGameType(GameType gameType) {
        delegate.setGameType(gameType);
    }

    @Override
    public TimerQueue<MinecraftServer> getScheduledEvents() {
        return delegate.getScheduledEvents();
    }

    @Override
    public void setGameTime(long gameTime) {
        delegate.setGameTime(gameTime);
    }

    @Override
    public void setDayTime(long dayTime) {
        time.setDayTime(dayTime);
    }
}
