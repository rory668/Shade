/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.studio.shade.qs.tiles;

import android.content.Intent;
import android.os.UserManager;

import android.provider.Settings;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.studio.shade.R;
import com.studio.shade.qs.QSTile;
import com.studio.shade.statusbar.policy.LocationController;
import com.studio.shade.statusbar.policy.LocationController.LocationSettingsChangeCallback;

/** Quick settings tile: Location **/
public class LocationTile extends QSTile<QSTile.BooleanState> {

    private final AnimationIcon mEnable =
            new AnimationIcon(R.drawable.ic_signal_location_enable_animation,
                    R.drawable.ic_signal_location_disable);
    private final AnimationIcon mDisable =
            new AnimationIcon(R.drawable.ic_signal_location_disable_animation,
                    R.drawable.ic_signal_location_enable);

    private final LocationController mController;
    private final Callback mCallback = new Callback();

    public LocationTile(Host host) {
        super(host);
        mController = host.getLocationController();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mController.addSettingsChangedCallback(mCallback);
        } else {
            mController.removeSettingsChangedCallback(mCallback);
        }
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
    }

    @Override
    protected void handleClick() {
        final boolean wasEnabled = (Boolean) mState.value;
        MetricsLogger.action(mContext, getMetricsCategory(), !wasEnabled);
        mController.setLocationEnabled(!wasEnabled);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_location_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean locationEnabled =  mController.isLocationEnabled();

        // Work around for bug 15916487: don't show location tile on top of lock screen. After the
        // bug is fixed, this should be reverted to only hiding it on secure lock screens:
        state.value = locationEnabled;
        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_SHARE_LOCATION);
        if (locationEnabled) {
            state.icon = mEnable;
            state.label = mContext.getString(R.string.quick_settings_location_label);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_location_on);
        } else {
            state.icon = mDisable;
            state.label = mContext.getString(R.string.quick_settings_location_label);
            state.contentDescription = mContext.getString(
                    R.string.accessibility_quick_settings_location_off);
        }
        state.minimalAccessibilityClassName = state.expandedAccessibilityClassName
                = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_LOCATION;
    }

    @Override
    protected String composeChangeAnnouncement() {
        if (mState.value) {
            return mContext.getString(R.string.accessibility_quick_settings_location_changed_on);
        } else {
            return mContext.getString(R.string.accessibility_quick_settings_location_changed_off);
        }
    }

    private final class Callback implements LocationSettingsChangeCallback {
        @Override
        public void onLocationSettingsChanged(boolean enabled) {
            refreshState();
        }
    };
}
