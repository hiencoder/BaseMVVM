package com.example.base.simplestack;


import androidx.annotation.NonNull;

import java.util.Map;

/**
 * A default strategy that clears the state for all keys that are not found in the new state.
 */
public class DefaultStateClearStrategy
        implements BackstackManager.StateClearStrategy {
    @Override
    public void clearStatesNotIn(@NonNull Map<Object, SavedState> keyStateMap, @NonNull StateChange stateChange) {
        keyStateMap.keySet().retainAll(stateChange.getNewState());
    }
}
