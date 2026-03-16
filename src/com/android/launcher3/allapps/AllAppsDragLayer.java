/*
 * Copyright (C) 2026 The halogenOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.allapps;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.Launcher;
import com.android.launcher3.dragndrop.DragController;
import com.android.launcher3.util.TouchController;
import com.android.launcher3.views.BaseDragLayer;

/**
 * Minimal drag layer for the AllApps separate window.
 * Hosts popup menus and DragViews so they appear above the blurred surface.
 * Forwards drag touch events to the launcher's DragController.
 */
public class AllAppsDragLayer extends BaseDragLayer<Launcher> {

    public AllAppsDragLayer(Context context) {
        this(context, null);
    }

    public AllAppsDragLayer(Context context, AttributeSet attrs) {
        super(context, attrs, 1);
        recreateControllers();
    }

    @Override
    public void recreateControllers() {
        mControllers = new TouchController[0];
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // Let floating views (popups) handle touches first
        AbstractFloatingView topView = findFloatingView();
        if (topView != null && topView.onControllerInterceptTouchEvent(ev)) {
            mActiveController = topView;
            return true;
        }

        // Forward to the launcher's DragController if a drag is in progress
        DragController<?> dc = mContainer.getDragController();
        if (dc != null && dc.isDragging() && dc.onControllerInterceptTouchEvent(ev)) {
            mActiveController = dc;
            return true;
        }

        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mActiveController != null) {
            return mActiveController.onControllerTouchEvent(ev);
        }

        // Forward to drag controller even if we didn't intercept
        DragController<?> dc = mContainer.getDragController();
        if (dc != null && dc.isDragging()) {
            return dc.onControllerTouchEvent(ev);
        }

        return false;
    }

    private AbstractFloatingView findFloatingView() {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            if (child instanceof AbstractFloatingView afv && afv.isOpen()) {
                return afv;
            }
        }
        return null;
    }
}
