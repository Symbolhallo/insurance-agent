package com.xxx.insurance.ai.agent.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OceanBaseAgentStateStoreTests {

    @Test
    void appliesDatabaseCasAndPreservesPreviousStateOnConflict() {
        InMemoryMapper mapper = new InMemoryMapper();
        OceanBaseAgentStateStore store = new OceanBaseAgentStateStore(mapper);

        long createdVersion = store.saveIfVersion(
                "user-1", "thread-1", "agent_state", new TestState("v1"), 0);
        long conflictVersion = store.saveIfVersion(
                "user-1", "thread-1", "agent_state", new TestState("invalid"), 0);
        long updatedVersion = store.saveIfVersion(
                "user-1", "thread-1", "agent_state", new TestState("v2"), createdVersion);

        assertThat(createdVersion).isEqualTo(1);
        assertThat(conflictVersion).isEqualTo(AgentStateStore.UNVERSIONED);
        assertThat(updatedVersion).isEqualTo(2);
        assertThat(store.getVersioned("user-1", "thread-1", "agent_state", TestState.class).value())
                .isEqualTo(new TestState("v2"));
    }

    @Test
    void storesAndRestoresFullStateLists() {
        OceanBaseAgentStateStore store = new OceanBaseAgentStateStore(new InMemoryMapper());

        store.save(null, "thread-1", "messages", List.of(new TestState("one"), new TestState("two")));

        assertThat(store.getList(null, "thread-1", "messages", TestState.class))
                .containsExactly(new TestState("one"), new TestState("two"));
    }

    private record TestState(String value) implements State {
    }

    private static final class InMemoryMapper implements AgentScopeStateMapper {

        private final Map<String, AgentScopeStateRecord> records = new HashMap<>();

        @Override
        public int upsert(String userId, String sessionId, String stateKey, String payload, boolean listValue) {
            String key = key(userId, sessionId, stateKey);
            AgentScopeStateRecord previous = records.get(key);
            long nextVersion = previous == null ? 1 : previous.stateVersion() + 1;
            records.put(key, new AgentScopeStateRecord(payload, listValue, nextVersion));
            return 1;
        }

        @Override
        public int insertIfAbsent(String userId, String sessionId, String stateKey, String payload) {
            String key = key(userId, sessionId, stateKey);
            if (records.containsKey(key)) {
                return 0;
            }
            records.put(key, new AgentScopeStateRecord(payload, false, 1));
            return 1;
        }

        @Override
        public int updateIfVersion(
                String userId, String sessionId, String stateKey, String payload, long expectedVersion) {
            String key = key(userId, sessionId, stateKey);
            AgentScopeStateRecord previous = records.get(key);
            if (previous == null || previous.stateVersion() != expectedVersion) {
                return 0;
            }
            records.put(key, new AgentScopeStateRecord(payload, false, expectedVersion + 1));
            return 1;
        }

        @Override
        public AgentScopeStateRecord find(String userId, String sessionId, String stateKey) {
            return records.get(key(userId, sessionId, stateKey));
        }

        @Override
        public int countSession(String userId, String sessionId) {
            String prefix = userId + "|" + sessionId + "|";
            return (int) records.keySet().stream().filter(key -> key.startsWith(prefix)).count();
        }

        @Override
        public int deleteSession(String userId, String sessionId) {
            int before = records.size();
            String prefix = userId + "|" + sessionId + "|";
            records.keySet().removeIf(key -> key.startsWith(prefix));
            return before - records.size();
        }

        @Override
        public int deleteState(String userId, String sessionId, String stateKey) {
            return records.remove(key(userId, sessionId, stateKey)) == null ? 0 : 1;
        }

        @Override
        public List<String> findSessionIds(String userId) {
            String prefix = userId + "|";
            return records.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .map(key -> key.substring(prefix.length(), key.lastIndexOf('|')))
                    .distinct()
                    .sorted()
                    .toList();
        }

        private String key(String userId, String sessionId, String stateKey) {
            return userId + "|" + sessionId + "|" + stateKey;
        }
    }
}

