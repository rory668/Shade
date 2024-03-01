/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.settingslib.location;

import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Retrieves the information of applications which accessed location recently.
 */
public class RecentLocationApps {
    private static final String TAG = RecentLocationApps.class.getSimpleName();
    private static final String ANDROID_SYSTEM_PACKAGE_NAME = "android";

    private static final int RECENT_TIME_INTERVAL_MILLIS = 15 * 60 * 1000;

    private static final int[] LOCATION_OPS = new int[] {
            AppOpsManager.OP_MONITOR_LOCATION,
            AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION,
    };

    private final PackageManager mPackageManager;
    private final Context mContext;

    public RecentLocationApps(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    /**
     * Fills a list of applications which queried location recently within specified time.
     */
    public List<Request> getAppList() {
        // Retrieve a location usage list from AppOps
        AppOpsManager aoManager =
                (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        List<AppOpsManager.PackageOps> appOps = aoManager.getPackagesForOps(LOCATION_OPS);

        final int appOpsCount = appOps != null ? appOps.size() : 0;

        // Process the AppOps list and generate a preference list.
        ArrayList<Request> requests = new ArrayList<>(appOpsCount);
        final long now = System.currentTimeMillis();
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        final List<UserHandle> profiles = um.getUserProfiles();

        for (int i = 0; i < appOpsCount; ++i) {
            AppOpsManager.PackageOps ops = appOps.get(i);
            // Don't show the Android System in the list - it's not actionable for the user.
            // Also don't show apps belonging to background users except managed users.
            String packageName = ops.getPackageName();
            int uid = ops.getUid();
            int userId = UserHandle.getUserId(uid);
            boolean isAndroidOs =
                    (uid == Process.SYSTEM_UID) && ANDROID_SYSTEM_PACKAGE_NAME.equals(packageName);
            if (isAndroidOs || !profiles.contains(new UserHandle(userId))) {
                continue;
            }
            Request request = getRequestFromOps(now, ops);
            if (request != null) {
                requests.add(request);
            }
        }

        return requests;
    }

    /**
     * Creates a Request entry for the given PackageOps.
     *
     * This method examines the time interval of the PackageOps first. If the PackageOps is older
     * than the designated interval, this method ignores the PackageOps object and returns null.
     * When the PackageOps is fresh enough, this method returns a Request object for the package
     */
    private Request getRequestFromOps(long now,
            AppOpsManager.PackageOps ops) {
        String packageName = ops.getPackageName();
        List<AppOpsManager.OpEntry> entries = ops.getOps();
        boolean highBattery = false;
        boolean normalBattery = false;
        // Earliest time for a location request to end and still be shown in list.
        long recentLocationCutoffTime = now - RECENT_TIME_INTERVAL_MILLIS;
        for (AppOpsManager.OpEntry entry : entries) {
            if (entry.isRunning() || entry.getTime() >= recentLocationCutoffTime) {
                switch (entry.getOp()) {
                    case AppOpsManager.OP_MONITOR_LOCATION:
                        normalBattery = true;
                        break;
                    case AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION:
                        highBattery = true;
                        break;
                    default:
                        break;
                }
            }
        }

        if (!highBattery && !normalBattery) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, packageName + " hadn't used location within the time interval.");
            }
            return null;
        }

        // The package is fresh enough, continue.

        int uid = ops.getUid();
        int userId = UserHandle.getUserId(uid);

        Request request = null;
        try {
            IPackageManager ipm = AppGlobals.getPackageManager();
            ApplicationInfo appInfo =
                    ipm.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId);
            if (appInfo == null) {
                Log.w(TAG, "Null application info retrieved for package " + packageName
                        + ", userId " + userId);
                return null;
            }

            final UserHandle userHandle = new UserHandle(userId);
            Drawable appIcon = mPackageManager.getApplicationIcon(appInfo);
            Drawable icon = mPackageManager.getUserBadgedIcon(appIcon, userHandle);
            CharSequence appLabel = mPackageManager.getApplicationLabel(appInfo);
            CharSequence badgedAppLabel = mPackageManager.getUserBadgedLabel(appLabel, userHandle);
            if (appLabel.toString().contentEquals(badgedAppLabel)) {
                // If badged label is not different from original then no need for it as
                // a separate content description.
                badgedAppLabel = null;
            }
            request = new Request(packageName, userHandle, icon, appLabel, highBattery,
                    badgedAppLabel);
        } catch (RemoteException e) {
            Log.w(TAG, "Error while retrieving application info for package " + packageName
                    + ", userId " + userId, e);
        }

        return request;
    }

    public static class Request {
        public final String packageName;
        public final UserHandle userHandle;
        public final Drawable icon;
        public final CharSequence label;
        public final boolean isHighBattery;
        public final CharSequence contentDescription;

        private Request(String packageName, UserHandle userHandle, Drawable icon,
                CharSequence label, boolean isHighBattery, CharSequence contentDescription) {
            this.packageName = packageName;
            this.userHandle = userHandle;
            this.icon = icon;
            this.label = label;
            this.isHighBattery = isHighBattery;
            this.contentDescription = contentDescription;
        }
    }
}
