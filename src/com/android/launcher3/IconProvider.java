package com.android.launcher3;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.launcher3.compat.LauncherActivityInfoCompat;
import com.android.launcher3.compat.UserHandleCompat;
import com.android.launcher3.compat.UserManagerCompat;

import java.lang.reflect.InvocationTargetException;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class IconProvider {

    private static final boolean DBG = false;
    private static final String TAG = "IconProvider";
    private static final String CALENDAR_PACKAGE = "com.google.android.calendar";
    private static final String CALENDAR_ROUND_ICONS = "com.google.android.calendar._icons_nexus_round";

    private BroadcastReceiver mBroadcastReceiver;
    protected PackageManager mPackageManager;
    protected String mSystemState;

    public IconProvider(Context context) {
        BroadcastReceiver mBroadcastReceiver = new IconProviderReceiver(this);
        IntentFilter intentFilter = new IntentFilter("android.intent.action.DATE_CHANGED");
        intentFilter.addAction("android.intent.action.TIME_SET");
        intentFilter.addAction("android.intent.action.TIMEZONE_CHANGED");
        context.registerReceiver(mBroadcastReceiver, intentFilter, null, new Handler(LauncherModel.getWorkerLooper()));
        mPackageManager = context.getPackageManager();

        updateSystemStateString();
    }

    public static IconProvider loadByName(String className, Context context) {
        if (TextUtils.isEmpty(className)) return new IconProvider(context);
        if (DBG) Log.d(TAG, "Loading IconProvider: " + className);
        try {
            Class<?> cls = Class.forName(className);
            return (IconProvider) cls.getDeclaredConstructor(Context.class).newInstance(context);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                | ClassCastException | NoSuchMethodException | InvocationTargetException e) {
            Log.e(TAG, "Bad IconProvider class", e);
            return new IconProvider(context);
        }
    }

    public void updateSystemStateString() {
        mSystemState = Locale.getDefault().toString();
    }

    public String getIconSystemState(String packageName) {
        if (isCalendar(packageName)) {
            return mSystemState + " " + dayOfMonth();
        }
        return mSystemState;
    }

    private int getCorrectShape(Bundle bundle, Resources resources) {
        if (bundle != null) {
            int roundIcons = bundle.getInt(CALENDAR_ROUND_ICONS, 0);
            if (roundIcons != 0) {
                try {
                    TypedArray obtainTypedArray = resources.obtainTypedArray(roundIcons);
                    int resourceId = obtainTypedArray.getResourceId(dayOfMonth(), 0);
                    obtainTypedArray.recycle();
                    return resourceId;
                } catch (Resources.NotFoundException e) {}
            }
        }

        return 0;
    }

    private int dayOfMonth() {
        return Calendar.getInstance().get(Calendar.DAY_OF_MONTH) - 1;
    }

    private boolean isCalendar(final String packageName) {
        return CALENDAR_PACKAGE.equals(packageName);
    }

    public Drawable getIcon(LauncherActivityInfoCompat info, int iconDpi) {
        Drawable drawable = null;
        String packageName = info.getApplicationInfo().packageName;
        if (isCalendar(packageName)) {
            try {
                ActivityInfo activityInfo = mPackageManager.getActivityInfo(info.getComponentName(),
                        PackageManager.GET_META_DATA | PackageManager.MATCH_UNINSTALLED_PACKAGES);
                Bundle metaData = activityInfo.metaData;
                Resources resourcesForApplication = mPackageManager.getResourcesForApplication(packageName);
                int shape = getCorrectShape(metaData, resourcesForApplication);
                if (shape != 0) {
                    drawable = resourcesForApplication.getDrawableForDensity(shape, iconDpi);
                }
            } catch (PackageManager.NameNotFoundException e) {}
        }
        return drawable != null ? drawable : info.getIcon(iconDpi);
    }

    class IconProviderReceiver extends BroadcastReceiver {
        IconProvider mIconProvider;

        IconProviderReceiver(final IconProvider IconProvider) {
            mIconProvider = IconProvider;
        }

        public void onReceive(final Context context, final Intent intent) {
            for (UserHandleCompat userHandleCompat : UserManagerCompat.getInstance(context).getUserProfiles()) {
                LauncherAppState instance = LauncherAppState.getInstance();
                instance.getModel().onPackageChanged(CALENDAR_PACKAGE, userHandleCompat);
                List queryForPinnedShortcuts = instance.getShortcutManager().queryForPinnedShortcuts(CALENDAR_PACKAGE, userHandleCompat);
                if (!queryForPinnedShortcuts.isEmpty()) {
                    instance.getModel().updatePinnedShortcuts(CALENDAR_PACKAGE, queryForPinnedShortcuts, userHandleCompat);
                }
            }
        }
    }
}
