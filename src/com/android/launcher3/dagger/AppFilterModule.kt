/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.dagger

import android.content.Context
import com.android.launcher3.AppFilter
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.lineage.trust.HiddenAppsFilter
import dagger.Module
import dagger.Provides

@Module
object AppFilterModule {

    @Provides
    fun provideAppFilter(@ApplicationContext context: Context): AppFilter {
        return HiddenAppsFilter(context)
    }
}
