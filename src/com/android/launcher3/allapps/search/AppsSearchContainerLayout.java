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
package com.android.launcher3.allapps.search;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getSize;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.RemoteException;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.WindowManagerGlobal;
import android.window.ScreenCaptureInternal;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Insettable;
import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.allapps.AllAppsStore;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.SearchUiManager;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;

/**
 * Layout to contain the All-apps search UI.
 */
public class AppsSearchContainerLayout extends ExtendedEditText
        implements SearchUiManager, SearchCallback<AdapterItem>,
        AllAppsStore.OnUpdateListener, Insettable {

    /**
     * AGSL shader for the pill background: samples the captured background bitmap,
     * darkens L in OKLCH, and renders the result as a filled shape.
     */
    private static final String BG_SHADER = """
            uniform shader background;
            uniform shader mask;
            uniform int isDark;
            uniform float2 bgScale;

            float srgbToLinear(float c) {
                return c <= 0.04045
                    ? c / 12.92
                    : pow((c + 0.055) / 1.055, 2.4);
            }

            float linearToSrgb(float c) {
                return c <= 0.0031308
                    ? c * 12.92
                    : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
            }

            half4 main(float2 coord) {
                float alpha = mask.eval(coord).a;
                if (alpha < 0.001) return half4(0.0);

                half4 src = background.eval(coord * bgScale);

                float r = srgbToLinear(src.r);
                float g = srgbToLinear(src.g);
                float b = srgbToLinear(src.b);

                float l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
                float m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
                float s = 0.0883024619 * r + 0.2220049174 * g + 0.6896926208 * b;

                float l_ = pow(max(l, 0.0), 1.0 / 3.0);
                float m_ = pow(max(m, 0.0), 1.0 / 3.0);
                float s_ = pow(max(s, 0.0), 1.0 / 3.0);

                float L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
                float A = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
                float B = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;

                float C = sqrt(A * A + B * B);
                float H = atan(B, A);

                if (alpha > 0.99) {
                    // Fill: darken or lighten via OKLCH
                    if (isDark == 1) {
                        L = L * 0.7 - 0.1;
                        L = clamp(L, 0.1, 0.5);
                    } else {
                        L = L * 0.3 + 0.7;
                        L = clamp(L, 0.5, 0.9);
                    }
                    C *= 1.1;

                    A = C * cos(H);
                    B = C * sin(H);

                    l_ = L + 0.3963377774 * A + 0.2158037573 * B;
                    m_ = L - 0.1055613458 * A - 0.0638541728 * B;
                    s_ = L - 0.0894841775 * A - 1.2914855480 * B;

                    l = l_ * l_ * l_;
                    m = m_ * m_ * m_;
                    s = s_ * s_ * s_;

                    r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
                    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
                    b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
                } else {
                    // Outline: HSL with max saturation
                    r = src.r; g = src.g; b = src.b;
                    float mx = max(max(r, g), b);
                    float mn = min(min(r, g), b);
                    float hh = 0.0;
                    float d = mx - mn;
                    if (d > 0.001) {
                        if (mx == r) hh = (g - b) / d + (g < b ? 6.0 : 0.0);
                        else if (mx == g) hh = (b - r) / d + 2.0;
                        else hh = (r - g) / d + 4.0;
                        hh /= 6.0;
                    }
                    float hslL = (mx + mn) / 2.0;
                    float origS = d < 0.001 ? 0.0
                        : d / (1.0 - abs(2.0 * hslL - 1.0 + 0.001));
                    origS = clamp(origS, 0.0, 1.0);
                    float newS = origS + origS * origS * origS;
                    newS = clamp(newS, 0.0, 1.0);
                    float c2 = (1.0 - abs(2.0 * hslL - 1.0)) * newS;
                    float x2 = c2 * (1.0 - abs(mod(hh * 6.0, 2.0) - 1.0));
                    float m2 = hslL - c2 / 2.0;
                    float hh6 = hh * 6.0;
                    if (hh6 < 1.0) { r = c2; g = x2; b = 0.0; }
                    else if (hh6 < 2.0) { r = x2; g = c2; b = 0.0; }
                    else if (hh6 < 3.0) { r = 0.0; g = c2; b = x2; }
                    else if (hh6 < 4.0) { r = 0.0; g = x2; b = c2; }
                    else if (hh6 < 5.0) { r = x2; g = 0.0; b = c2; }
                    else { r = c2; g = 0.0; b = x2; }
                    r += m2; g += m2; b += m2;
                }

                return half4(
                    linearToSrgb(clamp(r, 0.0, 1.0)),
                    linearToSrgb(clamp(g, 0.0, 1.0)),
                    linearToSrgb(clamp(b, 0.0, 1.0)),
                    1.0);
            }
            """;

    /**
     * AGSL shader that samples from a captured background and applies an OKLCH
     * color transform. Text glyphs act as the natural mask via Paint.setShader.
     */
    private static final String OKLCH_SHADER = """
            uniform shader background;
            uniform shader mask;
            uniform int isDark;
            uniform float2 bgScale;
            uniform float2 bgOffset;

            float srgbToLinear(float c) {
                return c <= 0.04045
                    ? c / 12.92
                    : pow((c + 0.055) / 1.055, 2.4);
            }

            float linearToSrgb(float c) {
                return c <= 0.0031308
                    ? c * 12.92
                    : 1.055 * pow(c, 1.0 / 2.4) - 0.055;
            }

            half4 main(float2 coord) {
                float alpha = mask.eval(coord).a;
                if (alpha < 0.001) return half4(0.0);

                half4 src = background.eval((coord + bgOffset) * bgScale);

                float r = srgbToLinear(src.r);
                float g = srgbToLinear(src.g);
                float b = srgbToLinear(src.b);

                float l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b;
                float m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b;
                float s = 0.0883024619 * r + 0.2220049174 * g + 0.6896926208 * b;

                float l_ = pow(max(l, 0.0), 1.0 / 3.0);
                float m_ = pow(max(m, 0.0), 1.0 / 3.0);
                float s_ = pow(max(s, 0.0), 1.0 / 3.0);

                float L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
                float A = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
                float B = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;

                float C = sqrt(A * A + B * B);
                float H = atan(B, A);

                float bgL = L;
                if (isDark == 1) {
                    L += 0.4;
                } else {
                    L -= 0.4;
                }
                L *= alpha;
                C = (C + C * C) * alpha;
                if (bgL < 0.5) {
                    L = clamp(L, 0.3, 1.0);
                } else {
                    L = clamp(L, 0.0, 0.7);
                }

                A = C * cos(H);
                B = C * sin(H);

                l_ = L + 0.3963377774 * A + 0.2158037573 * B;
                m_ = L - 0.1055613458 * A - 0.0638541728 * B;
                s_ = L - 0.0894841775 * A - 1.2914855480 * B;

                l = l_ * l_ * l_;
                m = m_ * m_ * m_;
                s = s_ * s_ * s_;

                r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
                g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
                b = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;

                return half4(
                    linearToSrgb(clamp(r, 0.0, 1.0)),
                    linearToSrgb(clamp(g, 0.0, 1.0)),
                    linearToSrgb(clamp(b, 0.0, 1.0)),
                    alpha);
            }
            """;

    private final ActivityContext mLauncher;
    private final AllAppsSearchBarController mSearchBarController;
    private final SpannableStringBuilder mSearchQueryBuilder;

    private ActivityAllAppsContainerView<?> mAppsView;

    // The amount of pixels to shift down and overlap with the rest of the content.
    private final int mContentOverlap;

    private RuntimeShader mTextShader;
    private RuntimeShader mBgShader;
    private final Paint mBgPaint;
    private final PillShapeDrawable mPillDrawable;
    private boolean mCaptureInFlight;
    private boolean mCaptureScheduled;
    private Bitmap mBackgroundCapture;

    public AppsSearchContainerLayout(Context context) {
        this(context, null);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mLauncher = ActivityContext.lookupContext(context);
        mSearchBarController = new AllAppsSearchBarController();

        mSearchQueryBuilder = new SpannableStringBuilder();
        Selection.setSelection(mSearchQueryBuilder, 0);
        mContentOverlap =
                getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_content_overlap);

        boolean isDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mTextShader = new RuntimeShader(OKLCH_SHADER);
        mTextShader.setIntUniform("isDark", isDark ? 1 : 0);
        mBgShader = new RuntimeShader(BG_SHADER);
        mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBgShader.setIntUniform("isDark", isDark ? 1 : 0);
        mPillDrawable = new PillShapeDrawable(getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAppsView.getAppsStore().addUpdateListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAppsView.getAppsStore().removeUpdateListener(this);
    }

    /**
     * Captures the composited display output behind this window.
     * The search bar lives in its own window; excluding its SC from the capture
     * gives us the AllApps blurred backdrop for OKLCH tinting.
     */
    public void requestBackgroundCapture() {
        if (mCaptureInFlight || mCaptureScheduled || getWidth() == 0 || getHeight() == 0) return;
        mCaptureScheduled = true;
        android.view.Choreographer.getInstance().postFrameCallback(frameTimeNanos -> {
            mCaptureScheduled = false;
            doBackgroundCapture();
        });
    }

    private void doBackgroundCapture() {
        if (mCaptureInFlight || getWidth() == 0 || getHeight() == 0) return;
        if (!(mLauncher instanceof Launcher launcher)) return;
        if (launcher.getDisplay() == null) return;
        int displayId = launcher.getDisplay().getDisplayId();

        SurfaceControl ownSc = getViewRootImpl() != null
                ? getViewRootImpl().getSurfaceControl() : null;
        if (ownSc == null || !ownSc.isValid()) return;

        mCaptureInFlight = true;

        ScreenCaptureInternal.CaptureArgs captureArgs =
                new ScreenCaptureInternal.CaptureArgs.Builder<>()
                        .setExcludeLayers(new SurfaceControl[]{ownSc})
                        .build();

        // Use the window root's bounds for the capture crop (not the EditText's),
        // since the pill view fills the entire window.
        View windowRoot = (View) getParent();
        final int[] loc = new int[2];
        windowRoot.getLocationOnScreen(loc);
        final int viewW = windowRoot.getWidth();
        final int viewH = windowRoot.getHeight();

        ScreenCaptureInternal.ScreenCaptureListener listener =
                new ScreenCaptureInternal.ScreenCaptureListener((result, status) -> {
                    // Process on background thread to avoid blocking UI
                    com.android.launcher3.util.Executors.THREAD_POOL_EXECUTOR.execute(() -> {
                        if (result == null) { post(() -> mCaptureInFlight = false); return; }
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        if (buffer == null) { post(() -> mCaptureInFlight = false); return; }

                        Bitmap hwBitmap = Bitmap.wrapHardwareBuffer(
                                buffer, result.getColorSpace());
                        buffer.close();
                        if (hwBitmap == null) { post(() -> mCaptureInFlight = false); return; }

                        Bitmap full = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        hwBitmap.recycle();
                        if (full == null) { post(() -> mCaptureInFlight = false); return; }

                        // Crop to search bar area and downscale for color data.
                        // Blur is handled natively by SF (setBackgroundBlurRadius on
                        // the AllApps window) — we only need approximate colors for
                        // the OKLCH tint.
                        int x = Math.max(0, loc[0]);
                        int y = Math.max(0, loc[1]);
                        int x2 = Math.min(full.getWidth(), loc[0] + viewW);
                        int y2 = Math.min(full.getHeight(), loc[1] + viewH);
                        int w = x2 - x;
                        int h = y2 - y;
                        if (w <= 0 || h <= 0) {
                            full.recycle();
                            post(() -> mCaptureInFlight = false);
                            return;
                        }

                        Bitmap finalBitmap = Bitmap.createBitmap(full, x, y, w, h);
                        full.recycle();

                        // Apply result on main thread
                        post(() -> {
                            mCaptureInFlight = false;
                            applyBackgroundCapture(finalBitmap);
                        });
                    });
                });

        try {
            WindowManagerGlobal.getWindowManagerService()
                    .captureDisplay(displayId, captureArgs, listener);
        } catch (RemoteException e) {
            Log.e("AppsSearch", "captureDisplay failed", e);
            mCaptureInFlight = false;
        }
    }

    private void applyBackgroundCapture(Bitmap capture) {
        mBackgroundCapture = capture;
        BitmapShader bgShader = new BitmapShader(
                capture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        View windowRoot = (View) getParent();
        float scaleX = (float) capture.getWidth() / windowRoot.getWidth();
        float scaleY = (float) capture.getHeight() / windowRoot.getHeight();

        // Pill: shape drawable + RenderEffect for fill + outline
        View pillView = windowRoot.findViewById(R.id.search_pill_bg);
        if (pillView != null) {
            pillView.setBackground(mPillDrawable);
            mBgShader.setInputShader("background", bgShader);
            mBgShader.setFloatUniform("bgScale", scaleX, scaleY);
            pillView.setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(
                    mBgShader, "mask"));
        }

        // Text: RenderEffect for adaptive text coloring
        setHintTextColor(0xFFFFFFFF);
        setTextColor(0xFFFFFFFF);
        setShadowLayer(0.001f, 1f, 1f, 0x80FFFFFF);

        mTextShader.setInputShader("background", bgShader);
        mTextShader.setFloatUniform("bgScale", scaleX, scaleY);
        mTextShader.setFloatUniform("bgOffset", 0f, 0f);

        setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(
                mTextShader, "mask"));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean isDark = (newConfig.uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mTextShader.setIntUniform("isDark", isDark ? 1 : 0);
        mBgShader.setIntUniform("isDark", isDark ? 1 : 0);
        requestBackgroundCapture();
    }

    private static int computeOklchColor(float r, float g, float b, boolean isDark) {
        r = srgbToLinear(r);
        g = srgbToLinear(g);
        b = srgbToLinear(b);

        float l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
        float s = 0.0883024619f * r + 0.2220049174f * g + 0.6896926208f * b;

        float l_ = (float) Math.cbrt(Math.max(l, 0));
        float m_ = (float) Math.cbrt(Math.max(m, 0));
        float s_ = (float) Math.cbrt(Math.max(s, 0));

        float L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_;
        float A = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_;
        float B = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_;

        float C = (float) Math.sqrt(A * A + B * B);
        float H = (float) Math.atan2(B, A);

        if (isDark) {
            L += 0.3f;
            C += 0.2f;
        } else {
            L -= 0.30f;
            C += 0.1f;
        }
        L = Math.max(0.30f, Math.min(0.70f, L));

        A = C * (float) Math.cos(H);
        B = C * (float) Math.sin(H);

        l_ = L + 0.3963377774f * A + 0.2158037573f * B;
        m_ = L - 0.1055613458f * A - 0.0638541728f * B;
        s_ = L - 0.0894841775f * A - 1.2914855480f * B;

        r = 4.0767416621f * l_ * l_ * l_ - 3.3077115913f * m_ * m_ * m_
                + 0.2309699292f * s_ * s_ * s_;
        g = -1.2684380046f * l_ * l_ * l_ + 2.6097574011f * m_ * m_ * m_
                - 0.3413193965f * s_ * s_ * s_;
        b = -0.0041960863f * l_ * l_ * l_ - 0.7034186147f * m_ * m_ * m_
                + 1.7076147010f * s_ * s_ * s_;

        int ri = Math.round(linearToSrgb(Math.max(0, Math.min(1, r))) * 255);
        int gi = Math.round(linearToSrgb(Math.max(0, Math.min(1, g))) * 255);
        int bi = Math.round(linearToSrgb(Math.max(0, Math.min(1, b))) * 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }


    private static float srgbToLinear(float c) {
        return c <= 0.04045f
                ? c / 12.92f
                : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
    }

    private static float linearToSrgb(float c) {
        return c <= 0.0031308f
                ? c * 12.92f
                : 1.055f * (float) Math.pow(c, 1.0 / 2.4) - 0.055f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Update the width to match the grid padding
        DeviceProfile dp = mLauncher.getDeviceProfile();
        int myRequestedWidth = getSize(widthMeasureSpec);
        int rowWidth = myRequestedWidth - mAppsView.getActiveRecyclerView().getPaddingLeft()
                - mAppsView.getActiveRecyclerView().getPaddingRight();

        int cellWidth = DeviceProfile.calculateCellWidth(rowWidth,
                dp.getWorkspaceIconProfile().getCellLayoutBorderSpacePx().x, dp.numShownHotseatIcons);
        int iconVisibleSize =
                Math.round(ICON_VISIBLE_AREA_FACTOR * dp.getWorkspaceIconProfile().getIconSizePx());
        int iconPadding = cellWidth - iconVisibleSize;

        int myWidth = rowWidth - iconPadding + getPaddingLeft() + getPaddingRight();
        super.onMeasure(makeMeasureSpec(myWidth, EXACTLY), heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Shift the widget horizontally so that its centered in the parent (b/63428078)
        View parent = (View) getParent();
        int availableWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        int myWidth = right - left;
        int expectedLeft = parent.getPaddingLeft() + (availableWidth - myWidth) / 2;
        int shift = expectedLeft - left;
        setTranslationX(shift);

        offsetTopAndBottom(mContentOverlap);
    }

    @Override
    public void initializeSearch(ActivityAllAppsContainerView<?> appsView) {
        mAppsView = appsView;
        mSearchBarController.initialize(
                new DefaultAppSearchAlgorithm(getContext(), true),
                this, mLauncher, this);
    }

    @Override
    public void onAppsUpdated() {
        mSearchBarController.refreshSearchResult();
    }

    @Override
    public void resetSearch() {
        mSearchBarController.reset();
    }

    @Override
    public void preDispatchKeyEvent(KeyEvent event) {
        // Determine if the key event was actual text, if so, focus the search bar and then dispatch
        // the key normally so that it can process this key event
        if (!mSearchBarController.isSearchFieldFocused() &&
                event.getAction() == KeyEvent.ACTION_DOWN) {
            final int unicodeChar = event.getUnicodeChar();
            final boolean isKeyNotWhitespace = unicodeChar > 0 &&
                    !Character.isWhitespace(unicodeChar) && !Character.isSpaceChar(unicodeChar);
            if (isKeyNotWhitespace) {
                boolean gotKey = TextKeyListener.getInstance().onKeyDown(this, mSearchQueryBuilder,
                        event.getKeyCode(), event);
                if (gotKey && mSearchQueryBuilder.length() > 0) {
                    mSearchBarController.focusSearchField();
                }
            }
        }
    }

    @Override
    public void onSearchResult(String query, ArrayList<AdapterItem> items) {
        if (items != null) {
            mAppsView.setSearchResults(items);
        }
    }

    @Override
    public void clearSearchResult() {
        // Clear the search query
        mSearchQueryBuilder.clear();
        mSearchQueryBuilder.clearSpans();
        Selection.setSelection(mSearchQueryBuilder, 0);
        mAppsView.onClearSearchResult();
    }

    @Override
    public void setInsets(Rect insets) {
        View target = getParent() instanceof ViewGroup ? (View) getParent() : this;
        MarginLayoutParams mlp = (MarginLayoutParams) target.getLayoutParams();
        int borderWidth = (int) Math.ceil(1f * getResources().getDisplayMetrics().density);
        mlp.topMargin = insets.top + borderWidth;
        target.requestLayout();
    }

    @Override
    public ExtendedEditText getEditText() {
        return this;
    }

    private static class PillShapeDrawable extends Drawable {
        private final Paint mFillPaint;
        private final Paint mStrokePaint;

        PillShapeDrawable(float density) {
            mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mFillPaint.setColor(0xFFFFFFFF);
            mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeWidth(2f * density);
            mStrokePaint.setColor(0xFAFFFFFF);
            mStrokePaint.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.SRC));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float radius = bounds.height() / 4f;
            canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    radius, radius, mFillPaint);
            float inset = mStrokePaint.getStrokeWidth() / 2f;
            canvas.drawRoundRect(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset,
                    radius, radius, mStrokePaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mFillPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            mFillPaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
