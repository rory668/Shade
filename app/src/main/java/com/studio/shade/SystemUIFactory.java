/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.studio.shade;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.widget.LockPatternUtils;
import com.studio.shade.statusbar.ScrimView;
import com.studio.shade.statusbar.phone.NotificationIconAreaController;
import com.studio.shade.statusbar.phone.PhoneStatusBar;
import com.studio.shade.statusbar.phone.QSTileHost;
import com.studio.shade.statusbar.phone.ScrimController;
import com.studio.shade.statusbar.phone.StatusBarIconController;
import com.studio.shade.statusbar.phone.StatusBarWindowManager;
import com.studio.shade.statusbar.policy.BatteryController;
import com.studio.shade.statusbar.policy.BluetoothController;
import com.studio.shade.statusbar.policy.CastController;
import com.studio.shade.statusbar.policy.FlashlightController;
import com.studio.shade.statusbar.policy.HotspotController;
import com.studio.shade.statusbar.policy.LocationController;
import com.studio.shade.statusbar.policy.NetworkController;
import com.studio.shade.statusbar.policy.NextAlarmController;
import com.studio.shade.statusbar.policy.RotationLockController;
import com.studio.shade.statusbar.policy.SecurityController;
import com.studio.shade.statusbar.policy.UserInfoController;
import com.studio.shade.statusbar.policy.UserSwitcherController;
import com.studio.shade.statusbar.policy.ZenModeController;

/**
 * Class factory to provide customizable SystemUI components.
 */
public class SystemUIFactory {
    private static final String TAG = "SystemUIFactory";

    static SystemUIFactory mFactory;

    public static SystemUIFactory getInstance() {
        return mFactory;
    }

    public static void createFromConfig(Context context) {
        final String clsName = context.getString(R.string.config_systemUIFactoryComponent);
        if (clsName == null || clsName.length() == 0) {
            throw new RuntimeException("No SystemUIFactory component configured");
        }

        try {
            Class<?> cls = null;
            cls = context.getClassLoader().loadClass(clsName);
            mFactory = (SystemUIFactory) cls.newInstance();
        } catch (Throwable t) {
            Log.w(TAG, "Error creating SystemUIFactory component: " + clsName, t);
            throw new RuntimeException(t);
        }
    }

    public SystemUIFactory() {}

    public ScrimController createScrimController(ScrimView scrimBehind, ScrimView scrimInFront,
            View headsUpScrim) {
        return new ScrimController(scrimBehind, scrimInFront, headsUpScrim);
    }

    public NotificationIconAreaController createNotificationIconAreaController(Context context,
            PhoneStatusBar phoneStatusBar) {
        return new NotificationIconAreaController(context, phoneStatusBar);
    }

    public QSTileHost createQSTileHost(Context context, PhoneStatusBar statusBar,
            BluetoothController bluetooth, LocationController location,
            RotationLockController rotation, NetworkController network,
            ZenModeController zen, HotspotController hotspot,
            CastController cast, FlashlightController flashlight,
            UserSwitcherController userSwitcher, UserInfoController userInfo,
            SecurityController security,
            BatteryController battery, StatusBarIconController iconController,
            NextAlarmController nextAlarmController) {
        return new QSTileHost(context, statusBar, bluetooth, location, rotation, network, zen,
                hotspot, cast, flashlight, userSwitcher, userInfo, security, battery,
                iconController, nextAlarmController);
    }

    public <T> T createInstance(Class<T> classType) {
        return null;
    }
}
