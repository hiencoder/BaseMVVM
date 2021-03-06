/*
 * Copyright 2017 Gabor Varadi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.base.simplestack;

import android.os.Parcelable;

import android.util.SparseArray;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.*;

/**
 * The backstack manager manages a {@link Backstack} internally, and wraps it with the ability of persisting view state and the backstack history itself.
 * <p>
 * The backstack is created by {@link BackstackManager#setup(List)}, and initialized by {@link BackstackManager#setStateChanger(StateChanger)}.
 */
public class BackstackManager
        implements Bundleable {
    private static final String HISTORY_TAG = "HISTORY";
    private static final String STATES_TAG = "STATES";
    private static final String SCOPES_TAG = "SCOPES";
    Backstack backstack;
    Map<Object, SavedState> keyStateMap = new HashMap<>();
    ScopeManager scopeManager = new ScopeManager();
    StateChanger stateChanger;
    private Object previousTopKeyWithAssociatedScope = null;
    private KeyFilter keyFilter = new DefaultKeyFilter();
    private KeyParceler keyParceler = new DefaultKeyParceler();
    private StateClearStrategy stateClearStrategy = new DefaultStateClearStrategy();
    private final StateChanger managedStateChanger = new StateChanger() {
        @Override
        public void handleStateChange(@NonNull final StateChange stateChange, @NonNull final Callback completionCallback) {
            scopeManager.buildScopes(stateChange.getNewState());
            stateChanger.handleStateChange(stateChange, new Callback() {
                @Override
                public void stateChangeComplete() {
                    completionCallback.stateChangeComplete();
                    if (!backstack.isStateChangePending()) {
                        stateClearStrategy.clearStatesNotIn(keyStateMap, stateChange);

                        History<Object> newState = stateChange.getNewState();

                        // activation/deactivation
                        Object newTopKeyWithAssociatedScope = null;
                        for (int i = 0, size = newState.size(); i < size; i++) {
                            Object key = newState.fromTop(i);
                            if (key instanceof ScopeKey || key instanceof ScopeKey.Child) {
                                newTopKeyWithAssociatedScope = key;
                                break;
                            }
                        }

                        Set<String> scopesToDeactivate = new LinkedHashSet<>();
                        Set<String> scopesToActivate = new LinkedHashSet<>();

                        if (previousTopKeyWithAssociatedScope != null) {
                            if (previousTopKeyWithAssociatedScope instanceof ScopeKey) {
                                ScopeKey scopeKey = (ScopeKey) previousTopKeyWithAssociatedScope;
                                scopesToDeactivate.add(scopeKey.getScopeTag());
                            }

                            if (previousTopKeyWithAssociatedScope instanceof ScopeKey.Child) {
                                ScopeKey.Child child = (ScopeKey.Child) previousTopKeyWithAssociatedScope;
                                ScopeManager.checkParentScopes(child);
                                List<String> parentScopes = child.getParentScopes();

                                for (int i = parentScopes.size() - 1; i >= 0; i--) {
                                    scopesToDeactivate.add(parentScopes.get(i));
                                }
                            }
                        }

                        if (newTopKeyWithAssociatedScope != null) {
                            if (newTopKeyWithAssociatedScope instanceof ScopeKey.Child) {
                                ScopeKey.Child child = (ScopeKey.Child) newTopKeyWithAssociatedScope;
                                ScopeManager.checkParentScopes(child);
                                scopesToActivate.addAll(child.getParentScopes());
                            }
                            if (newTopKeyWithAssociatedScope instanceof ScopeKey) {
                                ScopeKey scopeKey = (ScopeKey) newTopKeyWithAssociatedScope;
                                scopesToActivate.add(scopeKey.getScopeTag());
                            }
                        }

                        previousTopKeyWithAssociatedScope = newTopKeyWithAssociatedScope;

                        // do not deactivate scopes that exist at this time
                        Iterator<String> scopeToActivate = scopesToActivate.iterator();
                        while (scopeToActivate.hasNext()) {
                            String activeScope = scopeToActivate.next();
                            if (scopesToDeactivate.contains(activeScope)) {
                                scopesToDeactivate.remove(activeScope); // do not deactivate an already active scope
                                scopeToActivate.remove(); // if the previous already contains it, then it is already activated.
                                // we should make sure we never activate the same service twice.
                            }
                        }


                        if (!scopesToActivate.isEmpty() || !scopesToDeactivate.isEmpty()) { // de-morgan is an ass, but the unit tests don't lie
                            scopeManager.dispatchActivation(scopesToDeactivate, scopesToActivate);
                        }

                        // scope eviction + scoped
                        scopeManager.clearScopesNotIn(newState);
                    }
                }
            });
        }
    };

    /* init */ {
        scopeManager.setBackstackManager(this);
    }

    static String getHistoryTag() {
        return HISTORY_TAG;
    }

    static String getStatesTag() {
        return STATES_TAG;
    }

    static String getScopesTag() {
        return SCOPES_TAG;
    }

    /**
     * Specifies a custom {@link KeyFilter}, allowing keys to be filtered out if they should not be restored after process death.
     * <p>
     * If used, this method must be called before {@link BackstackManager#setup(List)} .
     *
     * @param keyFilter The custom {@link KeyFilter}.
     */
    public void setKeyFilter(@NonNull KeyFilter keyFilter) {
        if (backstack != null) {
            throw new IllegalStateException("Custom key filter should be set before calling `setup()`");
        }
        if (keyFilter == null) {
            throw new IllegalArgumentException("The key filter cannot be null!");
        }
        this.keyFilter = keyFilter;
    }

    /**
     * Specifies a custom {@link KeyParceler}, allowing key parcellation strategies to be used for turning a key into Parcelable.
     * <p>
     * If used, this method must be called before {@link BackstackManager#setup(List)} .
     *
     * @param keyParceler The custom {@link KeyParceler}.
     */
    public void setKeyParceler(@NonNull KeyParceler keyParceler) {
        if (backstack != null) {
            throw new IllegalStateException("Custom key parceler should be set before calling `setup()`");
        }
        if (keyParceler == null) {
            throw new IllegalArgumentException("The key parceler cannot be null!");
        }
        this.keyParceler = keyParceler;
    }

    /**
     * Specifies a custom {@link StateClearStrategy}, allowing a custom strategy for clearing the retained state of keys.
     * The {@link DefaultStateClearStrategy} clears the {@link SavedState} for keys that are not found in the new state.
     * <p>
     * If used, this method must be called before {@link BackstackManager#setup(List)} .
     *
     * @param stateClearStrategy The custom {@link StateClearStrategy}.
     */
    public void setStateClearStrategy(@NonNull StateClearStrategy stateClearStrategy) {
        if (backstack != null) {
            throw new IllegalStateException("Custom state clear strategy should be set before calling `setup()`");
        }
        if (stateClearStrategy == null) {
            throw new IllegalArgumentException("The state clear strategy cannot be null!");
        }
        this.stateClearStrategy = stateClearStrategy;
    }

    /**
     * Specifies a {@link ScopedServices} to allow handling the creation of scoped services.
     *
     * @param scopedServices the {@link ScopedServices}.
     */
    public void setScopedServices(@NonNull ScopedServices scopedServices) {
        if (backstack != null) {
            throw new IllegalStateException("Scope provider should be set before calling `setup()`");
        }
        if (scopedServices == null) {
            throw new IllegalArgumentException("The scope provider cannot be null!");
        }
        this.scopeManager.setScopedServices(scopedServices);
    }

    /**
     * Specifies a {@link GlobalServices} that describes the services of the global scope.
     *
     * @param globalServices the {@link GlobalServices}.
     */
    public void setGlobalServices(@NonNull GlobalServices globalServices) {
        if (backstack != null) {
            throw new IllegalStateException("Global scope should be set before calling `setup()`");
        }
        if (globalServices == null) {
            throw new IllegalArgumentException("The global services cannot be null!");
        }
        this.scopeManager.setGlobalServices(globalServices);
    }

    /**
     * Setup creates the {@link Backstack} with the specified initial keys.
     *
     * @param initialKeys the initial keys of the backstack
     */
    public void setup(@NonNull List<?> initialKeys) {
        backstack = new Backstack(initialKeys);
    }

    /**
     * Gets the managed {@link Backstack}. It can only be called after {@link BackstackManager#setup(List)}.
     *
     * @return the backstack
     */
    public Backstack getBackstack() {
        checkBackstack("You must call `setup()` before calling `getBackstack()`");
        return backstack;
    }

    private void initializeBackstack(StateChanger stateChanger) {
        if (stateChanger != null) {
            backstack.setStateChanger(managedStateChanger);
        }
    }

    /**
     * Sets the {@link StateChanger} for the given {@link Backstack}. This can only be called after {@link BackstackManager#setup(List)}.
     *
     * @param stateChanger the state changer
     */
    public void setStateChanger(@Nullable StateChanger stateChanger) {
        checkBackstack("You must call `setup()` before calling `setStateChanger().");
        if (backstack.hasStateChanger()) {
            backstack.removeStateChanger();
        }
        this.stateChanger = stateChanger;
        initializeBackstack(stateChanger);
    }

    /**
     * Detaches the {@link StateChanger} from the {@link Backstack}. This can only be called after {@link BackstackManager#setup(List)}.
     */
    public void detachStateChanger() {
        checkBackstack("You must call `setup()` before calling `detachStateChanger().`");
        if (backstack.hasStateChanger()) {
            backstack.removeStateChanger();
        }
    }

    /**
     * Reattaches the {@link StateChanger} to the {@link Backstack}. This can only be called after {@link BackstackManager#setup(List)}.
     */
    public void reattachStateChanger() {
        checkBackstack("You must call `setup()` before calling `reattachStateChanger().`");
        if (!backstack.hasStateChanger()) {
            backstack.setStateChanger(managedStateChanger, Backstack.REATTACH);
        }
    }

    /**
     * Typically called when Activity is finishing, and the remaining scopes should be destroyed for proper clean-up.
     * <p>
     * Note that if you use {@link BackstackDelegate} or {@link Navigator}, then there is no need to call this method manually.
     * <p>
     * If the scopes are already finalized, then calling this method has no effect (until scopes are re-built by any future navigation events).
     */
    public void finalizeScopes() {
        if (scopeManager.isFinalized()) {
            return;
        }

        if (previousTopKeyWithAssociatedScope != null) {
            Set<String> scopesToDeactivate = new LinkedHashSet<>();

            if (previousTopKeyWithAssociatedScope instanceof ScopeKey.Child) {
                ScopeKey.Child child = (ScopeKey.Child) previousTopKeyWithAssociatedScope;
                ScopeManager.checkParentScopes(child);
                List<String> parentScopes = new ArrayList<>(child.getParentScopes());
                scopesToDeactivate.addAll(parentScopes);
            }
            if (previousTopKeyWithAssociatedScope instanceof ScopeKey) {
                ScopeKey scopeKey = (ScopeKey) previousTopKeyWithAssociatedScope;
                scopesToDeactivate.add(scopeKey.getScopeTag());
            }

            List<String> scopesToDeactivateList = new ArrayList<>(scopesToDeactivate);
            Collections.reverse(scopesToDeactivateList);
            scopeManager.dispatchActivation(new LinkedHashSet<>(scopesToDeactivateList), Collections.<String>emptySet());
        }

        scopeManager.deactivateGlobalScope();

        History<Object> history = backstack.getHistory();
        Set<String> scopeSet = new LinkedHashSet<>();
        for (int i = 0, size = history.size(); i < size; i++) {
            Object key = history.fromTop(i);
            if (key instanceof ScopeKey) {
                scopeSet.add(((ScopeKey) key).getScopeTag());
            }
            if (key instanceof ScopeKey.Child) {
                ScopeKey.Child child = (ScopeKey.Child) key;
                List<String> parentScopes = new ArrayList<>(child.getParentScopes());
                Collections.reverse(parentScopes);
                for (String parent : parentScopes) {
                    //noinspection RedundantCollectionOperation
                    if (scopeSet.contains(parent)) {
                        scopeSet.remove(parent); // needed to setup the proper order
                    }
                    scopeSet.add(parent);
                }
            }
        }

        List<String> scopes = new ArrayList<>(scopeSet);
        for (String scope : scopes) {
            scopeManager.destroyScope(scope);
        }
        scopeManager.finalizeScopes();

        previousTopKeyWithAssociatedScope = null; // this enables activation after finalization.
    }

    /**
     * Returns if a service is bound to the scope of the {@link ScopeKey} by the provided tag.
     *
     * @param scopeKey   the scope key
     * @param serviceTag the service tag
     *
     * @return whether the service is bound in the given scope
     */
    public boolean hasService(@NonNull ScopeKey scopeKey, @NonNull String serviceTag) {
        return hasService(scopeKey.getScopeTag(), serviceTag);
    }

    /**
     * Returns the service bound to the scope of the {@link ScopeKey} by the provided tag.
     *
     * @param scopeKey   the scope key
     * @param serviceTag the service tag
     * @param <T>        the type of the service
     *
     * @return the service
     */
    @NonNull
    public <T> T getService(@NonNull ScopeKey scopeKey, @NonNull String serviceTag) {
        return getService(scopeKey.getScopeTag(), serviceTag);
    }

    /**
     * Returns if a service is bound to the scope specified by the provided tag for the provided service tag.
     *
     * @param scopeTag   the scope tag
     * @param serviceTag the service tag
     *
     * @return whether the service is bound in the given scope
     */
    public boolean hasService(@NonNull String scopeTag, @NonNull String serviceTag) {
        return scopeManager.hasService(scopeTag, serviceTag);
    }

    /**
     * Returns the service bound to the scope specified by the provided tag for the provided service tag.
     *
     * @param scopeTag   the scope tag
     * @param serviceTag the service tag
     * @param <T>        the type of the service
     *
     * @return the service
     */
    @NonNull
    public <T> T getService(@NonNull String scopeTag, @NonNull String serviceTag) {
        return scopeManager.getService(scopeTag, serviceTag);
    }

    /**
     * Returns if a given scope exists.
     *
     * @param scopeTag the scope tag
     *
     * @return whether the scope exists
     */
    public boolean hasScope(@NonNull String scopeTag) {
        return scopeManager.hasScope(scopeTag);
    }

    /**
     * Attempts to look-up the service in all currently existing scopes, starting from the last added scope.
     * Returns whether the service exists in any scopes.
     *
     * @param serviceTag the tag of the service
     *
     * @return whether the service exists in any active scopes
     */
    public boolean canFindService(@NonNull String serviceTag) {
        return scopeManager.canFindService(serviceTag);
    }

    /**
     * Attempts to look-up the service in the provided scope and all its parents, starting from the provided scope.
     * Returns whether the service exists in any of these scopes.
     *
     * @param scopeTag   the tag of the scope to look up from
     * @param serviceTag the tag of the service
     *
     * @return whether the service exists in any scopes from the current scope or its parents
     */
    public boolean canFindFromScope(@NonNull String scopeTag, @NonNull String serviceTag) {
        return scopeManager.canFindFromScope(scopeTag, serviceTag, ScopeLookupMode.ALL);
    }

    /**
     * Attempts to look-up the service in the provided scope and the specified type of parents, starting from the provided scope.
     * Returns whether the service exists in any of these scopes.
     *
     * @param scopeTag   the tag of the scope to look up from
     * @param serviceTag the tag of the service
     * @param lookupMode determine what type of parents are checked during the lookup
     *
     * @return whether the service exists in any scopes from the current scope or its parents
     */
    public boolean canFindFromScope(@NonNull String scopeTag, @NonNull String serviceTag, @NonNull ScopeLookupMode lookupMode) {
        return scopeManager.canFindFromScope(scopeTag, serviceTag, lookupMode);
    }

    /**
     * Attempts to look-up the service in all currently existing scopes, starting from the last added scope.
     * If the service is not found, an exception is thrown.
     *
     * @param serviceTag the tag of the service
     * @param <T>        the type of the service
     *
     * @return the service
     *
     * @throws IllegalStateException if the service doesn't exist in any scope
     */
    @NonNull
    public <T> T lookupService(@NonNull String serviceTag) {
        return scopeManager.lookupService(serviceTag);
    }

    /**
     * Returns a list of the scopes accessible from the given key.
     * <p>
     * The order of the scopes is that the 0th index is the current scope (if available), followed by parents.
     *
     * @param key        the key
     * @param lookupMode determine what type of parents are checked during the lookup
     *
     * @return the list of scope tags
     */
    @NonNull
    public List<String> findScopesForKey(@NonNull Object key, @NonNull ScopeLookupMode lookupMode) {
        Set<String> scopes = scopeManager.findScopesForKey(key, lookupMode);
        return Collections.unmodifiableList(new ArrayList<>(scopes));
    }

    /**
     * Attempts to look-up the service in the provided scope and its parents, starting from the provided scope.
     * If the service is not found, an exception is thrown.
     *
     * @param serviceTag the tag of the service
     * @param <T>        the type of the service
     *
     * @return the service
     *
     * @throws IllegalStateException if the service doesn't exist in any of the scopes
     */
    @NonNull
    public <T> T lookupFromScope(String scopeTag, String serviceTag) {
        return scopeManager.lookupFromScope(scopeTag, serviceTag, ScopeLookupMode.ALL);
    }

    /**
     * Attempts to look-up the service in the provided scope and its parents, starting from the provided scope.
     * If the service is not found, an exception is thrown.
     *
     * @param serviceTag the tag of the service
     * @param <T>        the type of the service
     * @param lookupMode determine what type of parents are checked during the lookup
     *
     * @return the service
     *
     * @throws IllegalStateException if the service doesn't exist in any of the scopes
     */
    @NonNull
    public <T> T lookupFromScope(String scopeTag, String serviceTag, ScopeLookupMode lookupMode) {
        return scopeManager.lookupFromScope(scopeTag, serviceTag, lookupMode);
    }

    /**
     * Returns a {@link SavedState} instance for the given key.
     * If the state does not exist, then a new associated state is created.
     *
     * @param key The key to which the {@link SavedState} belongs.
     *
     * @return the saved state that belongs to the given key.
     */
    @NonNull
    public SavedState getSavedState(@NonNull Object key) {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null!");
        }
        if (!keyStateMap.containsKey(key)) {
            keyStateMap.put(key, SavedState.builder().setKey(key).build());
        }
        return keyStateMap.get(key);
    }

    /**
     * Provides the means to save the provided view's hierarchy state
     * and its optional StateBundle via {@link Bundleable} into a {@link SavedState}.
     *
     * @param view the view that belongs to a certain key
     */
    public void persistViewToState(@Nullable View view) {
        if (view != null) {
            Object key = KeyContextWrapper.getKey(view.getContext());
            if (key == null) {
                throw new IllegalArgumentException("The view [" + view + "] contained no key in its context hierarchy. The view or its parent hierarchy should be inflated by a layout inflater from `stateChange.createContext(baseContext, key)`, or a KeyContextWrapper.");
            }
            SparseArray<Parcelable> viewHierarchyState = new SparseArray<>();
            view.saveHierarchyState(viewHierarchyState);
            StateBundle bundle = null;
            if (view instanceof Bundleable) {
                bundle = ((Bundleable) view).toBundle();
            }
            SavedState previousSavedState = SavedState.builder() //
                    .setKey(key) //
                    .setViewHierarchyState(viewHierarchyState) //
                    .setViewBundle(bundle) //
                    .build();
            keyStateMap.put(key, previousSavedState);
        }
    }

    // ----- viewstate persistence

    /**
     * Restores the state of the view based on the currently stored {@link SavedState}, according to the view's key.
     *
     * @param view the view that belongs to a certain key
     */
    public void restoreViewFromState(@NonNull View view) {
        if (view == null) {
            throw new IllegalArgumentException("You cannot restore state into null view!");
        }
        Object newKey = KeyContextWrapper.getKey(view.getContext());
        SavedState savedState = getSavedState(newKey);
        view.restoreHierarchyState(savedState.getViewHierarchyState());
        if (view instanceof Bundleable) {
            ((Bundleable) view).fromBundle(savedState.getViewBundle());
        }
    }

    /**
     * Allows adding a {@link Backstack.CompletionListener} to the internal {@link Backstack} that is called when the state change is completed, but before the state is cleared.
     * <p>
     * Please note that a strong reference is kept to the listener, and the {@link Backstack} is typically preserved across configuration change.
     * It is recommended that it is NOT an anonymous inner class or normal inner class in an Activity,
     * because that could cause memory leaks.
     * <p>
     * Instead, it should be a class, or a static inner class.
     *
     * @param stateChangeCompletionListener the state change completion listener.
     */
    public void addStateChangeCompletionListener(@NonNull Backstack.CompletionListener stateChangeCompletionListener) {
        checkBackstack("A backstack must be set up before a state change completion listener is added to it.");
        if (stateChangeCompletionListener == null) {
            throw new IllegalArgumentException("StateChangeCompletionListener cannot be null!");
        }
        this.backstack.addCompletionListener(stateChangeCompletionListener);
    }

    /**
     * Removes the provided {@link Backstack.CompletionListener}.
     *
     * @param stateChangeCompletionListener the state change completion listener.
     */
    public void removeStateChangeCompletionListener(@NonNull Backstack.CompletionListener stateChangeCompletionListener) {
        checkBackstack("A backstack must be set up before a state change completion listener is removed from it.");
        if (stateChangeCompletionListener == null) {
            throw new IllegalArgumentException("StateChangeCompletionListener cannot be null!");
        }
        this.backstack.removeCompletionListener(stateChangeCompletionListener);
    }

    /**
     * Removes all {@link Backstack.CompletionListener}s added to the {@link Backstack}.
     */
    public void removeAllStateChangeCompletionListeners() {
        checkBackstack("A backstack must be set up before state change completion listeners are removed from it.");
        this.backstack.removeCompletionListeners();
    }

    /**
     * Restores the BackstackManager from a StateBundle.
     * This can only be called after {@link BackstackManager#setup(List)}.
     *
     * @param stateBundle the state bundle obtained via {@link BackstackManager#toBundle()}
     */
    @Override
    public void fromBundle(@Nullable StateBundle stateBundle) {
        checkBackstack("A backstack must be set up before it is restored!");
        if (stateBundle != null) {
            List<Object> keys = new ArrayList<>();
            List<Parcelable> parcelledKeys = stateBundle.getParcelableArrayList(getHistoryTag());
            if (parcelledKeys != null) {
                for (Parcelable parcelledKey : parcelledKeys) {
                    keys.add(keyParceler.fromParcelable(parcelledKey));
                }
            }
            keys = keyFilter.filterHistory(new ArrayList<>(keys));
            if (keys == null) {
                keys = Collections.emptyList(); // lenient against null
            }
            if (!keys.isEmpty()) {
                backstack.setInitialParameters(keys);
            }
            List<ParcelledState> savedStates = stateBundle.getParcelableArrayList(getStatesTag());
            if (savedStates != null) {
                for (ParcelledState parcelledState : savedStates) {
                    Object key = keyParceler.fromParcelable(parcelledState.parcelableKey);
                    if (!keys.contains(key)) {
                        continue;
                    }
                    SavedState savedState = SavedState.builder().setKey(key)
                            .setViewHierarchyState(parcelledState.viewHierarchyState)
                            .setBundle(parcelledState.bundle)
                            .setViewBundle(parcelledState.viewBundle)
                            .build();
                    keyStateMap.put(savedState.getKey(), savedState);
                }
            }

            scopeManager.setRestoredStates(stateBundle.getBundle(SCOPES_TAG));
        }
    }

    private void checkBackstack(String message) {
        if (backstack == null) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Persists the backstack history and view state into a StateBundle.
     *
     * @return the state bundle
     */
    @NonNull
    @Override
    public StateBundle toBundle() {
        StateBundle stateBundle = new StateBundle();
        ArrayList<Parcelable> history = new ArrayList<>();
        for (Object key : backstack.getHistory()) {
            history.add(keyParceler.toParcelable(key));
        }
        stateBundle.putParcelableArrayList(getHistoryTag(), history);

        ArrayList<ParcelledState> states = new ArrayList<>();
        for (SavedState savedState : keyStateMap.values()) {
            ParcelledState parcelledState = new ParcelledState();
            parcelledState.parcelableKey = keyParceler.toParcelable(savedState.getKey());
            parcelledState.viewHierarchyState = savedState.getViewHierarchyState();
            parcelledState.bundle = savedState.getBundle();
            parcelledState.viewBundle = savedState.getViewBundle();
            states.add(parcelledState);
        }
        stateBundle.putParcelableArrayList(getStatesTag(), states);

        stateBundle.putParcelable(getScopesTag(), scopeManager.saveStates());
        return stateBundle;
    }

    /**
     * Specifies the strategy to be used in order to delete {@link SavedState}s that are no longer needed after a {@link StateChange}, when there is no pending {@link StateChange} left.
     */
    public interface StateClearStrategy {
        /**
         * Allows a hook to clear the {@link SavedState} for obsolete keys.
         *
         * @param keyStateMap the map that contains the keys and their corresponding retained saved state.
         * @param stateChange the last state change
         */
        void clearStatesNotIn(@NonNull Map<Object, SavedState> keyStateMap, @NonNull StateChange stateChange);
    }
}
