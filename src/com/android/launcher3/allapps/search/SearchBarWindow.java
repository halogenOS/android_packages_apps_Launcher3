/*
 * Copyright (C) 2026 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.allapps.search;

import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import com.android.launcher3.Launcher;
import com.android.launcher3.R;

/**
 * Hosts the search bar in a separate window so it has its own SurfaceControl.
 * This allows the capture to exclude only the search bar when sampling the
 * blurred backdrop for OKLCH tinting.
 */
public class SearchBarWindow {

    private final Launcher mLauncher;
    private final WindowManager mWindowManager;
    private final LayoutParams mLayoutParams;
    private View mRootView;
    private boolean mAttached;

    public SearchBarWindow(Launcher launcher) {
        mLauncher = launcher;
        mWindowManager = launcher.getSystemService(WindowManager.class);
        mLayoutParams = createLayoutParams();
    }

    private LayoutParams createLayoutParams() {
        LayoutParams lp = new LayoutParams(
                0, 0,
                LayoutParams.TYPE_APPLICATION_PANEL,
                LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | LayoutParams.FLAG_NOT_FOCUSABLE
                        | LayoutParams.FLAG_NOT_TOUCHABLE
                        | LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("SearchBar");
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.layoutInDisplayCutoutMode =
                LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        return lp;
    }

    public void setVisible(boolean visible) {
        if (!mAttached) return;
        if (visible) {
            mLayoutParams.flags &= ~(LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            mLayoutParams.flags |= LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        mRootView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mWindowManager.updateViewLayout(mRootView, mLayoutParams);
    }

    /** Inflates the search bar layout without adding the window. */
    public View inflate() {
        mRootView = LayoutInflater.from(mLauncher)
                .inflate(R.layout.search_container_all_apps, null);
        mRootView.setVisibility(View.INVISIBLE);
        return mRootView;
    }

    /** Adds the window. Call after other panel windows are attached for correct z-order. */
    public void attach() {
        if (mRootView == null) return;
        View decorView = mLauncher.getWindow().getDecorView();
        if (decorView.getWindowToken() != null) {
            addWindow(decorView.getWindowToken());
        } else {
            decorView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    decorView.removeOnAttachStateChangeListener(this);
                    if (decorView.getWindowToken() != null) {
                        addWindow(decorView.getWindowToken());
                    }
                }
                @Override
                public void onViewDetachedFromWindow(View v) {}
            });
        }
    }

    private void addWindow(android.os.IBinder token) {
        mLayoutParams.token = token;
        mWindowManager.addView(mRootView, mLayoutParams);
        mAttached = true;
    }

    public void detach() {
        if (mAttached) {
            mWindowManager.removeViewImmediate(mRootView);
            mAttached = false;
        }
    }

    public void updatePosition(int x, int y, int width, int height) {
        if (!mAttached) return;
        mLayoutParams.x = x;
        mLayoutParams.y = y;
        mLayoutParams.width = width;
        mLayoutParams.height = height;
        mWindowManager.updateViewLayout(mRootView, mLayoutParams);
    }

    public boolean isAttached() {
        return mAttached;
    }

    public View getRootView() {
        return mRootView;
    }
}
