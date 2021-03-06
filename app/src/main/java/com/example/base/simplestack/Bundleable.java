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


import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


/**
 * Specifies that the custom view that implements this also places its persisted state into a StateBundle.
 * <p>
 * This is used by {@link BackstackDelegate#persistViewToState(View)} and {@link BackstackDelegate#restoreViewFromState(View)}.
 */
public interface Bundleable {
    @NonNull
    StateBundle toBundle();

    void fromBundle(@Nullable StateBundle bundle);
}
