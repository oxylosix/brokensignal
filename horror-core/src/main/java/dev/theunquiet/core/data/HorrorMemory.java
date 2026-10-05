package dev.theunquiet.core.data;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.server.level.ServerLevel;

public final class HorrorMemory extends SavedData {
    private static final String DATA_ID = "the_unquiet_memory";
    private static final int HISTORY_LIMIT = 64;
    private static final int STATE_LIMIT = 128;
    private static final int CHAIN_LIMIT = 32;
    private static final int KEY_LIMIT = 128;
    private static final int VALUE_LIMIT = 512;
    private static final int COOLDOWN_LIMIT = 256;
    private static final Factory<HorrorMemory> FACTORY =
            new Factory<>(HorrorMemory::new, HorrorMemory::load, DataFixTypes.LEVEL);

    private final Map<UUID, PlayerRecord> players = new HashMap<>();

    public static HorrorMemory get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public long activeTicks(UUID playerId) {
        return record(playerId).activeTicks;
    }

    public void addActiveTicks(UUID playerId, long ticks) {
        if (ticks <= 0) {
            return;
        }
        record(playerId).activeTicks += ticks;
        setDirty();
    }

    public boolean hasSeen(UUID playerId, String eventId) {
        return record(playerId).history.contains(eventId);
    }

    public long lastSeenAt(UUID playerId, String eventId) {
        return record(playerId).lastSeen.getOrDefault(eventId, Long.MIN_VALUE);
    }

    public long lastGlobalEventTick(UUID playerId) {
        return record(playerId).lastGlobalEventTick;
    }

    public List<String> history(UUID playerId) {
        return List.copyOf(record(playerId).history);
    }

    public void recordEvent(UUID playerId, String eventId, long worldTime) {
        PlayerRecord record = record(playerId);
        record.lastSeen.put(eventId, worldTime);
        record.lastGlobalEventTick = worldTime;
        record.history.remove(eventId);
        record.history.addLast(eventId);
        while (record.history.size() > HISTORY_LIMIT) {
            record.history.removeFirst();
        }
        record.eventCount++;
        setDirty();
    }

    public int eventCount(UUID playerId) {
        return record(playerId).eventCount;
    }

    public String getState(UUID playerId, String key, String defaultValue) {
        return record(playerId).state.getOrDefault(key, defaultValue);
    }

    public void setState(UUID playerId, String key, String value) {
        validateStateEntry(key, Objects.requireNonNull(value));
        PlayerRecord record = record(playerId);
        if (!record.state.containsKey(key) && record.state.size() >= STATE_LIMIT) {
            throw new IllegalStateException("Player horror state limit reached");
        }
        record.state.put(key, value);
        setDirty();
    }

    public void clearState(UUID playerId, String key) {
        if (record(playerId).state.remove(key) != null) {
            setDirty();
        }
    }

    public String chainStage(UUID playerId, String chainId) {
        return record(playerId).chainStages.getOrDefault(chainId, "");
    }

    public void setChainStage(UUID playerId, String chainId, String stageId) {
        validateStateEntry(chainId, Objects.requireNonNull(stageId));
        PlayerRecord record = record(playerId);
        if (!record.chainStages.containsKey(chainId) && record.chainStages.size() >= CHAIN_LIMIT) {
            throw new IllegalStateException("Player event-chain limit reached");
        }
        record.chainStages.put(chainId, stageId);
        setDirty();
    }

    private static void validateStateEntry(String key, String value) {
        if (key.isBlank() || key.length() > KEY_LIMIT || value.length() > VALUE_LIMIT) {
            throw new IllegalArgumentException("Invalid player horror state entry");
        }
    }

    private PlayerRecord record(UUID playerId) {
        return players.computeIfAbsent(playerId, ignored -> new PlayerRecord());
    }

    private static HorrorMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        HorrorMemory memory = new HorrorMemory();
        CompoundTag storedPlayers = tag.getCompound("players");
        for (String key : storedPlayers.getAllKeys()) {
            try {
                UUID playerId = UUID.fromString(key);
                CompoundTag playerTag = storedPlayers.getCompound(key);
                PlayerRecord record = new PlayerRecord();
                record.activeTicks = playerTag.getLong("active_ticks");
                record.eventCount = playerTag.getInt("event_count");
                record.lastGlobalEventTick = playerTag.getLong("last_global_event_tick");
                CompoundTag lastSeen = playerTag.getCompound("last_seen");
                for (String eventId : lastSeen.getAllKeys().stream().limit(COOLDOWN_LIMIT).toList()) {
                    record.lastSeen.put(eventId, lastSeen.getLong(eventId));
                }
                ListTag history = playerTag.getList("history", StringTag.TAG_STRING);
                for (int i = Math.max(0, history.size() - HISTORY_LIMIT); i < history.size(); i++) {
                    record.history.addLast(history.getString(i));
                }
                CompoundTag state = playerTag.getCompound("state");
                for (String stateKey : state.getAllKeys().stream().limit(STATE_LIMIT).toList()) {
                    record.state.put(stateKey, state.getString(stateKey));
                }
                CompoundTag chainStages = playerTag.getCompound("chain_stages");
                for (String chainId : chainStages.getAllKeys().stream().limit(CHAIN_LIMIT).toList()) {
                    record.chainStages.put(chainId, chainStages.getString(chainId));
                }
                memory.players.put(playerId, record);
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed UUID keys in a damaged or manually edited save.
            }
        }
        return memory;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag storedPlayers = new CompoundTag();
        for (Map.Entry<UUID, PlayerRecord> entry : players.entrySet()) {
            PlayerRecord record = entry.getValue();
            CompoundTag playerTag = new CompoundTag();
            playerTag.putLong("active_ticks", record.activeTicks);
            playerTag.putInt("event_count", record.eventCount);
            playerTag.putLong("last_global_event_tick", record.lastGlobalEventTick);

            CompoundTag lastSeen = new CompoundTag();
            record.lastSeen.forEach(lastSeen::putLong);
            playerTag.put("last_seen", lastSeen);

            ListTag history = new ListTag();
            record.history.forEach(id -> history.add(StringTag.valueOf(id)));
            playerTag.put("history", history);
            CompoundTag state = new CompoundTag();
            record.state.forEach(state::putString);
            playerTag.put("state", state);
            CompoundTag chainStages = new CompoundTag();
            record.chainStages.forEach(chainStages::putString);
            playerTag.put("chain_stages", chainStages);
            storedPlayers.put(entry.getKey().toString(), playerTag);
        }
        tag.put("players", storedPlayers);
        return tag;
    }

    private static final class PlayerRecord {
        private long activeTicks;
        private long lastGlobalEventTick = Long.MIN_VALUE;
        private int eventCount;
        private final Map<String, Long> lastSeen = new HashMap<>();
        private final ArrayDeque<String> history = new ArrayDeque<>();
        private final Map<String, String> state = new HashMap<>();
        private final Map<String, String> chainStages = new HashMap<>();
    }
}
