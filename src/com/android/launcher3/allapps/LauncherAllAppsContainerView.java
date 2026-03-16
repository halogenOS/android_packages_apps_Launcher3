/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.allapps;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.R;
import com.android.launcher3.allapps.search.SearchBarWindow;
import com.android.launcher3.statemanager.StateManager;

/**
 * AllAppsContainerView with launcher specific callbacks.
 *
 * Hosts close-gesture detection so the user can swipe down to dismiss
 * the drawer even though it lives in a separate window.
 */
public class LauncherAllAppsContainerView extends ActivityAllAppsContainerView<Launcher> {

    private static final float CLOSE_THRESHOLD = 0.15f;
    private static final float FLING_VELOCITY_PX_S = 800f;
    private static final long SETTLE_DURATION_MS = 250;

    private final int mTouchSlop;
    private final Rect mTmpRect = new Rect();
    private SearchBarWindow mSearchBarWindow;
    private View mSearchBarSpacer;

    private float mDownY;
    private float mLastY;
    private boolean mDraggingToClose;
    private boolean mDecidedIntercept;
    private VelocityTracker mVelocityTracker;

    public LauncherAllAppsContainerView(Context context) {
        this(context, null);
    }

    public LauncherAllAppsContainerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public LauncherAllAppsContainerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected View inflateSearchBar() {
        // Inflate the real search bar into its own window so it has a separate
        // SurfaceControl for capture exclusion.
        Launcher launcher = (Launcher) mActivityContext;
        mSearchBarWindow = new SearchBarWindow(launcher);
        View windowRoot = mSearchBarWindow.inflate();
        View searchEditText = windowRoot.findViewById(R.id.search_edit_text);
        mSearchUiManager = (SearchUiManager) searchEditText;

        // Return a spacer that reserves the search bar's space in the layout.
        // Match the dimensions from search_container_all_apps.xml.
        mSearchBarSpacer = new View(getContext());
        mSearchBarSpacer.setId(R.id.search_container_all_apps);
        float density = getResources().getDisplayMetrics().density;
        int margin = Math.round(16 * density);
        int height = getResources().getDimensionPixelSize(
                R.dimen.all_apps_search_bar_field_height);
        android.widget.LinearLayout.LayoutParams spacerLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, height);
        spacerLp.setMarginStart(margin);
        spacerLp.setMarginEnd(margin);
        spacerLp.bottomMargin = margin;
        mSearchBarSpacer.setLayoutParams(spacerLp);

        // Position the search bar window over the spacer when it lays out.
        mSearchBarSpacer.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or2, ob) -> {
                    int[] loc = new int[2];
                    v.getLocationOnScreen(loc);
                    mSearchBarWindow.updatePosition(loc[0], loc[1],
                            v.getWidth(), v.getHeight());
                });

        return mSearchBarSpacer;
    }

    public void updateSearchBarPosition() {
        if (mSearchBarWindow == null || mSearchBarSpacer == null) return;
        if (mSearchBarSpacer.getWidth() == 0) return;
        int[] loc = new int[2];
        mSearchBarSpacer.getLocationOnScreen(loc);
        mSearchBarWindow.updatePosition(loc[0], loc[1],
                mSearchBarSpacer.getWidth(), mSearchBarSpacer.getHeight());
    }

    public void attachSearchBarWindow() {
        if (mSearchBarWindow != null) {
            mSearchBarWindow.attach();
        }
    }

    public void setSearchBarVisible(boolean visible) {
        if (mSearchBarWindow != null) {
            mSearchBarWindow.setVisible(visible);
        }
    }

    public void detachSearchBarWindow() {
        if (mSearchBarWindow != null) {
            mSearchBarWindow.detach();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isInAllApps()) {
            return super.onInterceptTouchEvent(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownY = ev.getRawY();
                mLastY = mDownY;
                mDraggingToClose = false;
                mDecidedIntercept = false;
                if (mVelocityTracker != null) mVelocityTracker.recycle();
                mVelocityTracker = VelocityTracker.obtain();
                mVelocityTracker.addMovement(ev);
                break;

            case MotionEvent.ACTION_MOVE:
                if (mDecidedIntercept) break;
                float dy = ev.getRawY() - mDownY;
                if (Math.abs(dy) > mTouchSlop && dy > 0) {
                    mDecidedIntercept = true;
                    if (shouldDragDrawer(ev)) {
                        mDraggingToClose = true;
                        mLastY = ev.getRawY();
                        return true;
                    }
                }
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDraggingToClose) {
            return super.onTouchEvent(ev);
        }

        if (mVelocityTracker != null) mVelocityTracker.addMovement(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                float displacement = ev.getRawY() - mDownY;
                float screenHeight = mActivityContext.getDeviceProfile()
                        .getDeviceProperties().getHeightPx();
                float progress = Math.max(0f, Math.min(1f, displacement / screenHeight));
                mActivityContext.getAllAppsController().setProgress(progress);
                mLastY = ev.getRawY();
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                settleDrawer(ev);
                mDraggingToClose = false;
                break;
        }
        return true;
    }

    private boolean shouldDragDrawer(MotionEvent ev) {
        if (isEventOverView(mSearchContainer, ev)
                || isEventOverView(mBottomSheetBackground, ev)) {
            return true;
        }
        AllAppsRecyclerView rv = getActiveRecyclerView();
        return rv == null || !rv.canScrollVertically(-1);
    }

    private boolean isEventOverView(View view, MotionEvent ev) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        view.getGlobalVisibleRect(mTmpRect);
        return mTmpRect.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    private void settleDrawer(MotionEvent ev) {
        float velocity = 0;
        if (mVelocityTracker != null) {
            mVelocityTracker.computeCurrentVelocity(1000);
            velocity = mVelocityTracker.getYVelocity();
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }

        AllAppsTransitionController controller = mActivityContext.getAllAppsController();
        float currentProgress = controller.getProgress();

        boolean close = velocity > FLING_VELOCITY_PX_S
                || (velocity >= 0 && currentProgress > CLOSE_THRESHOLD);

        float targetProgress = close ? 1f : 0f;
        ValueAnimator animator = ValueAnimator.ofFloat(currentProgress, targetProgress);
        animator.setDuration(SETTLE_DURATION_MS);
        animator.addUpdateListener(a ->
                controller.setProgress((float) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (close) {
                    mActivityContext.getStateManager().goToState(
                            LauncherState.NORMAL, false);
                }
            }
        });
        animator.start();
    }

    @Override
    protected int computeNavBarScrimHeight(WindowInsets insets) {
        return insets.getTappableElementInsets().bottom;
    }

    @Override
    public boolean isInAllApps() {
        return mActivityContext.getStateManager().isInStableState(LauncherState.ALL_APPS);
    }

    @Override
    public boolean shouldFloatingSearchBarBePillWhenUnfocused() {
        if (!isSearchBarFloating()) {
            return false;
        }
        Launcher launcher = mActivityContext;
        StateManager<LauncherState, Launcher> manager = launcher.getStateManager();
        if (manager.isInTransition() && manager.getTargetState() != null) {
            return manager.getTargetState().shouldFloatingSearchBarUsePillWhenUnfocused(launcher);
        }
        return manager.getCurrentStableState()
                .shouldFloatingSearchBarUsePillWhenUnfocused(launcher);
    }

    @Override
    public int getFloatingSearchBarRestingMarginBottom() {
        if (!isSearchBarFloating()) {
            return super.getFloatingSearchBarRestingMarginBottom();
        }
        Launcher launcher = mActivityContext;
        StateManager<LauncherState, Launcher> stateManager = launcher.getStateManager();

        // We want to rest at the current state's resting position, unless we are in transition and
        // the target state's resting position is higher (that way if we are closing the keyboard,
        // we can stop translating at that point).
        int currentStateMarginBottom = stateManager.getCurrentStableState()
                .getFloatingSearchBarRestingMarginBottom(launcher);
        int targetStateMarginBottom = -1;
        if (stateManager.isInTransition() && stateManager.getTargetState() != null) {
            targetStateMarginBottom = stateManager.getTargetState()
                    .getFloatingSearchBarRestingMarginBottom(launcher);
            if (targetStateMarginBottom < 0) {
                // Go ahead and move offscreen.
                return targetStateMarginBottom;
            }
        }
        return Math.max(targetStateMarginBottom, currentStateMarginBottom);
    }

    @Override
    public int getFloatingSearchBarRestingMarginStart() {
        if (!isSearchBarFloating()) {
            return super.getFloatingSearchBarRestingMarginStart();
        }

        StateManager<LauncherState, Launcher> stateManager = mActivityContext.getStateManager();

        // Special case to not expand the search bar when exiting All Apps on phones.
        if (stateManager.getCurrentStableState() == LauncherState.ALL_APPS
                && mActivityContext.getDeviceProfile().getDeviceProperties().isPhone()) {
            return LauncherState.ALL_APPS.getFloatingSearchBarRestingMarginStart(mActivityContext);
        }

        if (stateManager.isInTransition() && stateManager.getTargetState() != null) {
            return stateManager.getTargetState()
                    .getFloatingSearchBarRestingMarginStart(mActivityContext);
        }
        return stateManager.getCurrentStableState()
                .getFloatingSearchBarRestingMarginStart(mActivityContext);
    }

    @Override
    public int getFloatingSearchBarRestingMarginEnd() {
        if (!isSearchBarFloating()) {
            return super.getFloatingSearchBarRestingMarginEnd();
        }

        StateManager<LauncherState, Launcher> stateManager = mActivityContext.getStateManager();

        // Special case to not expand the search bar when exiting All Apps on phones.
        if (stateManager.getCurrentStableState() == LauncherState.ALL_APPS
                && mActivityContext.getDeviceProfile().getDeviceProperties().isPhone()) {
            return LauncherState.ALL_APPS.getFloatingSearchBarRestingMarginEnd(mActivityContext);
        }

        if (stateManager.isInTransition() && stateManager.getTargetState() != null) {
            return stateManager.getTargetState()
                    .getFloatingSearchBarRestingMarginEnd(mActivityContext);
        }
        return stateManager.getCurrentStableState()
                .getFloatingSearchBarRestingMarginEnd(mActivityContext);
    }
}
