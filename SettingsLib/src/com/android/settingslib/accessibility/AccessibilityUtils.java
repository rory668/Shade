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
 * limitations under the License
 */

package com.android.settingslib.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AccessibilityUtils {
    public static final char ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR = ':';

    final static TextUtils.SimpleStringSplitter sStringColonSplitter =
            new TextUtils.SimpleStringSplitter(ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR);

    /**
     * @return the set of enabled accessibility services. If there are no services,
     * it returns the unmodifiable {@link Collections#emptySet()}.
     */
    public static Set<ComponentName> getEnabledServicesFromSettings(Context context) {
        return getEnabledServicesFromSettings(context, UserHandle.myUserId());
    }

    /**
     * @return the set of enabled accessibility services for {@param userId}. If there are no
     * services, it returns the unmodifiable {@link Collections#emptySet()}.
     */
    public static Set<ComponentName> getEnabledServicesFromSettings(Context context, int userId) {
        final String enabledServicesSetting = Settings.Secure.getStringForUser(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                userId);
        if (enabledServicesSetting == null) {
            return Collections.emptySet();
        }

        final Set<ComponentName> enabledServices = new HashSet<>();
        final TextUtils.SimpleStringSplitter colonSplitter = sStringColonSplitter;
        colonSplitter.setString(enabledServicesSetting);

        while (colonSplitter.hasNext()) {
            final String componentNameString = colonSplitter.next();
            final ComponentName enabledService = ComponentName.unflattenFromString(
                    componentNameString);
            if (enabledService != null) {
                enabledServices.add(enabledService);
            }
        }

        return enabledServices;
    }

    /**
     * @return a localized version of the text resource specified by resId
     */
    public static CharSequence getTextForLocale(Context context, Locale locale, int resId) {
        final Resources res = context.getResources();
        final Configuration config = new Configuration(res.getConfiguration());
        config.setLocale(locale);
        final Context langContext = context.createConfigurationContext(config);
        return langContext.getText(resId);
    }

    /**
     * Changes an accessibility component's state.
     */
    public static void setAccessibilityServiceState(Context context, ComponentName toggledService,
            boolean enabled) {
        setAccessibilityServiceState(context, toggledService, enabled, UserHandle.myUserId());
    }

    /**
     * Changes an accessibility component's state for {@param userId}.
     */
    public static void setAccessibilityServiceState(Context context, ComponentName toggledService,
            boolean enabled, int userId) {
        // Parse the enabled services.
        Set<ComponentName> enabledServices = AccessibilityUtils.getEnabledServicesFromSettings(
                context, userId);

        if (enabledServices.isEmpty()) {
            enabledServices = new ArraySet<>(1);
        }

        // Determine enabled services and accessibility state.
        boolean accessibilityEnabled = false;
        if (enabled) {
            enabledServices.add(toggledService);
            // Enabling at least one service enables accessibility.
            accessibilityEnabled = true;
        } else {
            enabledServices.remove(toggledService);
            // Check how many enabled and installed services are present.
            Set<ComponentName> installedServices = getInstalledServices(context);
            for (ComponentName enabledService : enabledServices) {
                if (installedServices.contains(enabledService)) {
                    // Disabling the last service disables accessibility.
                    accessibilityEnabled = true;
                    break;
                }
            }
        }

        // Update the enabled services setting.
        StringBuilder enabledServicesBuilder = new StringBuilder();
        // Keep the enabled services even if they are not installed since we
        // have no way to know whether the application restore process has
        // completed. In general the system should be responsible for the
        // clean up not settings.
        for (ComponentName enabledService : enabledServices) {
            enabledServicesBuilder.append(enabledService.flattenToString());
            enabledServicesBuilder.append(
                    AccessibilityUtils.ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR);
        }
        final int enabledServicesBuilderLength = enabledServicesBuilder.length();
        if (enabledServicesBuilderLength > 0) {
            enabledServicesBuilder.deleteCharAt(enabledServicesBuilderLength - 1);
        }
        Settings.Secure.putStringForUser(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enabledServicesBuilder.toString(), userId);
    }

    private static Set<ComponentName> getInstalledServices(Context context) {
        final Set<ComponentName> installedServices = new HashSet<>();
        installedServices.clear();

        final List<AccessibilityServiceInfo> installedServiceInfos =
                AccessibilityManager.getInstance(context)
                        .getInstalledAccessibilityServiceList();
        if (installedServiceInfos == null) {
            return installedServices;
        }

        for (final AccessibilityServiceInfo info : installedServiceInfos) {
            final ResolveInfo resolveInfo = info.getResolveInfo();
            final ComponentName installedService = new ComponentName(
                    resolveInfo.serviceInfo.packageName,
                    resolveInfo.serviceInfo.name);
            installedServices.add(installedService);
        }
        return installedServices;
    }

}
