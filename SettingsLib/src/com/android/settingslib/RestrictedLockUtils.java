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
 * limitations under the License.
 */

package com.android.settingslib;

import android.app.AppGlobals;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.Spanned;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.view.MenuItem;
import android.widget.TextView;

import com.android.internal.widget.LockPatternUtils;

import java.util.List;

/**
 * Utility class to host methods usable in adding a restricted padlock icon and showing admin
 * support message dialog.
 */
public class RestrictedLockUtils {
    /**
     * @return drawables for displaying with settings that are locked by a device admin.
     */
    public static Drawable getRestrictedPadlock(Context context) {
        Drawable restrictedPadlock = context.getDrawable(R.drawable.ic_info);
        final int iconSize = context.getResources().getDimensionPixelSize(
                R.dimen.restricted_icon_size);
        restrictedPadlock.setBounds(0, 0, iconSize, iconSize);
        return restrictedPadlock;
    }

    /**
     * Checks if a restriction is enforced on a user and returns the enforced admin and
     * admin userId.
     *
     * @param userRestriction Restriction to check
     * @param userId User which we need to check if restriction is enforced on.
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} If the restriction is not set. If the restriction is set by both device owner
     * and profile owner, then the admin component will be set to {@code null} and userId to
     * {@link UserHandle#USER_NULL}.
     */
    public static EnforcedAdmin checkIfRestrictionEnforced(Context context,
            String userRestriction, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        UserManager um = UserManager.get(context);
        int restrictionSource = um.getUserRestrictionSource(userRestriction,
                UserHandle.of(userId));

        // If the restriction is not enforced or enforced only by system then return null
        if (restrictionSource == UserManager.RESTRICTION_NOT_SET
                || restrictionSource == UserManager.RESTRICTION_SOURCE_SYSTEM) {
            return null;
        }

        final boolean enforcedByProfileOwner =
                (restrictionSource & UserManager.RESTRICTION_SOURCE_PROFILE_OWNER) != 0;
        final boolean enforcedByDeviceOwner =
                (restrictionSource & UserManager.RESTRICTION_SOURCE_DEVICE_OWNER) != 0;
        if (enforcedByProfileOwner) {
            return getProfileOwner(context, userId);
        } else if (enforcedByDeviceOwner) {
            // When the restriction is enforced by device owner, return the device owner admin only
            // if the admin is for the {@param userId} otherwise return a default EnforcedAdmin.
            final EnforcedAdmin deviceOwner = getDeviceOwner(context);
            return deviceOwner.userId == userId
                    ? deviceOwner
                    : EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
        }
        return null;
    }

    public static boolean hasBaseUserRestriction(Context context,
            String userRestriction, int userId) {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        return um.hasBaseUserRestriction(userRestriction, UserHandle.of(userId));
    }

    /**
     * Checks if keyguard features are disabled by policy.
     *
     * @param keyguardFeatures Could be any of keyguard features that can be
     * disabled by {@link android.app.admin.DevicePolicyManager#setKeyguardDisabledFeatures}.
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} If the notification features are not disabled. If the restriction is set by
     * multiple admins, then the admin component will be set to {@code null} and userId to
     * {@link UserHandle#USER_NULL}.
     */
    public static EnforcedAdmin checkIfKeyguardFeaturesDisabled(Context context,
            int keyguardFeatures, int userId) {
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        final UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        LockPatternUtils lockPatternUtils = new LockPatternUtils(context);
        EnforcedAdmin enforcedAdmin = null;
        if (um.getUserInfo(userId).isManagedProfile()) {
            final List<ComponentName> admins = dpm.getActiveAdminsAsUser(userId);
            if (admins == null) {
                return null;
            }
            for (ComponentName admin : admins) {
                if ((dpm.getKeyguardDisabledFeatures(admin, userId) & keyguardFeatures) != 0) {
                    if (enforcedAdmin == null) {
                        enforcedAdmin = new EnforcedAdmin(admin, userId);
                    } else {
                        return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                    }
                }
            }
        } else {
            // Consider all admins for this user and the profiles that are visible from this
            // user that do not use a separate work challenge.
            for (UserInfo userInfo : um.getProfiles(userId)) {
                final List<ComponentName> admins = dpm.getActiveAdminsAsUser(userInfo.id);
                if (admins == null) {
                    continue;
                }
                final boolean isSeparateProfileChallengeEnabled =
                        lockPatternUtils.isSeparateProfileChallengeEnabled(userInfo.id);
                for (ComponentName admin : admins) {
                    if (!isSeparateProfileChallengeEnabled) {
                        if ((dpm.getKeyguardDisabledFeatures(admin, userInfo.id)
                                    & keyguardFeatures) != 0) {
                            if (enforcedAdmin == null) {
                                enforcedAdmin = new EnforcedAdmin(admin, userInfo.id);
                            } else {
                                return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                            }
                            // This same admins could have set policies both on the managed profile
                            // and on the parent. So, if the admin has set the policy on the
                            // managed profile here, we don't need to further check if that admin
                            // has set policy on the parent admin.
                            continue;
                        }
                    }
                    if (userInfo.isManagedProfile()) {
                        // If userInfo.id is a managed profile, we also need to look at
                        // the policies set on the parent.
                        DevicePolicyManager parentDpm = dpm.getParentProfileInstance(userInfo);
                        if ((parentDpm.getKeyguardDisabledFeatures(admin, userInfo.id)
                                & keyguardFeatures) != 0) {
                            if (enforcedAdmin == null) {
                                enforcedAdmin = new EnforcedAdmin(admin, userInfo.id);
                            } else {
                                return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                            }
                        }
                    }
                }
            }
        }
        return enforcedAdmin;
    }

    public static EnforcedAdmin checkIfUninstallBlocked(Context context,
            String packageName, int userId) {
        EnforcedAdmin allAppsControlDisallowedAdmin = checkIfRestrictionEnforced(context,
                UserManager.DISALLOW_APPS_CONTROL, userId);
        if (allAppsControlDisallowedAdmin != null) {
            return allAppsControlDisallowedAdmin;
        }
        EnforcedAdmin allAppsUninstallDisallowedAdmin = checkIfRestrictionEnforced(context,
                UserManager.DISALLOW_UNINSTALL_APPS, userId);
        if (allAppsUninstallDisallowedAdmin != null) {
            return allAppsUninstallDisallowedAdmin;
        }
        IPackageManager ipm = AppGlobals.getPackageManager();
        try {
            if (ipm.getBlockUninstallForUser(packageName, userId)) {
                return getProfileOrDeviceOwner(context, userId);
            }
        } catch (RemoteException e) {
            // Nothing to do
        }
        return null;
    }

    /**
     * Check if an application is suspended.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} if the application is not suspended.
     */
    public static EnforcedAdmin checkIfApplicationIsSuspended(Context context, String packageName,
            int userId) {
        IPackageManager ipm = AppGlobals.getPackageManager();
        try {
            if (ipm.isPackageSuspendedForUser(packageName, userId)) {
                return getProfileOrDeviceOwner(context, userId);
            }
        } catch (RemoteException | IllegalArgumentException e) {
            // Nothing to do
        }
        return null;
    }

    public static EnforcedAdmin checkIfInputMethodDisallowed(Context context,
            String packageName, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        EnforcedAdmin admin = getProfileOrDeviceOwner(context, userId);
        boolean permitted = true;
        if (admin != null) {
            permitted = dpm.isInputMethodPermittedByAdmin(admin.component,
                    packageName, userId);
        }
        int managedProfileId = getManagedProfileId(context, userId);
        EnforcedAdmin profileAdmin = getProfileOrDeviceOwner(context, managedProfileId);
        boolean permittedByProfileAdmin = true;
        if (profileAdmin != null) {
            permittedByProfileAdmin = dpm.isInputMethodPermittedByAdmin(profileAdmin.component,
                    packageName, managedProfileId);
        }
        if (!permitted && !permittedByProfileAdmin) {
            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
        } else if (!permitted) {
            return admin;
        } else if (!permittedByProfileAdmin) {
            return profileAdmin;
        }
        return null;
    }

    /**
     * @param context
     * @param userId user id of a managed profile.
     * @return is remote contacts search disallowed.
     */
    public static EnforcedAdmin checkIfRemoteContactSearchDisallowed(Context context, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        EnforcedAdmin admin = getProfileOwner(context, userId);
        if (admin == null) {
            return null;
        }
        UserHandle userHandle = UserHandle.of(userId);
        if (dpm.getCrossProfileContactsSearchDisabled(userHandle)
                && dpm.getCrossProfileCallerIdDisabled(userHandle)) {
            return admin;
        }
        return null;
    }

    public static EnforcedAdmin checkIfAccessibilityServiceDisallowed(Context context,
            String packageName, int userId) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        EnforcedAdmin admin = getProfileOrDeviceOwner(context, userId);
        boolean permitted = true;
        if (admin != null) {
            permitted = dpm.isAccessibilityServicePermittedByAdmin(admin.component,
                    packageName, userId);
        }
        int managedProfileId = getManagedProfileId(context, userId);
        EnforcedAdmin profileAdmin = getProfileOrDeviceOwner(context, managedProfileId);
        boolean permittedByProfileAdmin = true;
        if (profileAdmin != null) {
            permittedByProfileAdmin = dpm.isAccessibilityServicePermittedByAdmin(
                    profileAdmin.component, packageName, managedProfileId);
        }
        if (!permitted && !permittedByProfileAdmin) {
            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
        } else if (!permitted) {
            return admin;
        } else if (!permittedByProfileAdmin) {
            return profileAdmin;
        }
        return null;
    }

    private static int getManagedProfileId(Context context, int userId) {
        UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
        List<UserInfo> userProfiles = um.getProfiles(userId);
        for (UserInfo uInfo : userProfiles) {
            if (uInfo.id == userId) {
                continue;
            }
            if (uInfo.isManagedProfile()) {
                return uInfo.id;
            }
        }
        return UserHandle.USER_NULL;
    }

    /**
     * Check if account management for a specific type of account is disabled by admin.
     * Only a profile or device owner can disable account management. So, we check if account
     * management is disabled and return profile or device owner on the calling user.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} if the account management is not disabled.
     */
    public static EnforcedAdmin checkIfAccountManagementDisabled(Context context,
            String accountType, int userId) {
        if (accountType == null) {
            return null;
        }
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        boolean isAccountTypeDisabled = false;
        String[] disabledTypes = dpm.getAccountTypesWithManagementDisabledAsUser(userId);
        for (String type : disabledTypes) {
            if (accountType.equals(type)) {
                isAccountTypeDisabled = true;
                break;
            }
        }
        if (!isAccountTypeDisabled) {
            return null;
        }
        return getProfileOrDeviceOwner(context, userId);
    }

    /**
     * Checks if {@link android.app.admin.DevicePolicyManager#setAutoTimeRequired} is enforced
     * on the device.
     *
     * @return EnforcedAdmin Object containing the device owner component and
     * userId the device owner is running as, or {@code null} setAutoTimeRequired is not enforced.
     */
    public static EnforcedAdmin checkIfAutoTimeRequired(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.getAutoTimeRequired()) {
            return null;
        }
        ComponentName adminComponent = dpm.getDeviceOwnerComponentOnCallingUser();
        return new EnforcedAdmin(adminComponent, UserHandle.myUserId());
    }

    /**
     * Checks if an admin has enforced minimum password quality requirements on the given user.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} if no quality requirements are set. If the requirements are set by
     * multiple device admins, then the admin component will be set to {@code null} and userId to
     * {@link UserHandle#USER_NULL}.
     *
     */
    public static EnforcedAdmin checkIfPasswordQualityIsSet(Context context, int userId) {
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }

        LockPatternUtils lockPatternUtils = new LockPatternUtils(context);
        EnforcedAdmin enforcedAdmin = null;
        if (lockPatternUtils.isSeparateProfileChallengeEnabled(userId)) {
            // userId is managed profile and has a separate challenge, only consider
            // the admins in that user.
            final List<ComponentName> admins = dpm.getActiveAdminsAsUser(userId);
            if (admins == null) {
                return null;
            }
            for (ComponentName admin : admins) {
                if (dpm.getPasswordQuality(admin, userId)
                        > DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                    if (enforcedAdmin == null) {
                        enforcedAdmin = new EnforcedAdmin(admin, userId);
                    } else {
                        return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                    }
                }
            }
        } else {
            // Return all admins for this user and the profiles that are visible from this
            // user that do not use a separate work challenge.
            final UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            for (UserInfo userInfo : um.getProfiles(userId)) {
                final List<ComponentName> admins = dpm.getActiveAdminsAsUser(userInfo.id);
                if (admins == null) {
                    continue;
                }
                final boolean isSeparateProfileChallengeEnabled =
                        lockPatternUtils.isSeparateProfileChallengeEnabled(userInfo.id);
                for (ComponentName admin : admins) {
                    if (!isSeparateProfileChallengeEnabled) {
                        if (dpm.getPasswordQuality(admin, userInfo.id)
                                > DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                            if (enforcedAdmin == null) {
                                enforcedAdmin = new EnforcedAdmin(admin, userInfo.id);
                            } else {
                                return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                            }
                            // This same admins could have set policies both on the managed profile
                            // and on the parent. So, if the admin has set the policy on the
                            // managed profile here, we don't need to further check if that admin
                            // has set policy on the parent admin.
                            continue;
                        }
                    }
                    if (userInfo.isManagedProfile()) {
                        // If userInfo.id is a managed profile, we also need to look at
                        // the policies set on the parent.
                        DevicePolicyManager parentDpm = dpm.getParentProfileInstance(userInfo);
                        if (parentDpm.getPasswordQuality(admin, userInfo.id)
                                > DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                            if (enforcedAdmin == null) {
                                enforcedAdmin = new EnforcedAdmin(admin, userInfo.id);
                            } else {
                                return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                            }
                        }
                    }
                }
            }
        }
        return enforcedAdmin;
    }

    /**
     * Checks if any admin has set maximum time to lock.
     *
     * @return EnforcedAdmin Object containing the enforced admin component and admin user details,
     * or {@code null} if no admin has set this restriction. If multiple admins has set this, then
     * the admin component will be set to {@code null} and userId to {@link UserHandle#USER_NULL}
     */
    public static EnforcedAdmin checkIfMaximumTimeToLockIsSet(Context context) {
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        LockPatternUtils lockPatternUtils = new LockPatternUtils(context);
        EnforcedAdmin enforcedAdmin = null;
        final int userId = UserHandle.myUserId();
        final UserManager um = UserManager.get(context);
        final List<UserInfo> profiles = um.getProfiles(userId);
        final int profilesSize = profiles.size();
        // As we do not have a separate screen lock timeout settings for work challenge,
        // we need to combine all profiles maximum time to lock even work challenge is
        // enabled.
        for (int i = 0; i < profilesSize; i++) {
            final UserInfo userInfo = profiles.get(i);
            final List<ComponentName> admins = dpm.getActiveAdminsAsUser(userInfo.id);
            if (admins == null) {
                continue;
            }
            for (ComponentName admin : admins) {
                if (dpm.getMaximumTimeToLock(admin, userInfo.id) > 0) {
                    if (enforcedAdmin == null) {
                        enforcedAdmin = new EnforcedAdmin(admin, userInfo.id);
                    } else {
                        return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                    }
                    // This same admins could have set policies both on the managed profile
                    // and on the parent. So, if the admin has set the policy on the
                    // managed profile here, we don't need to further check if that admin
                    // has set policy on the parent admin.
                    continue;
                }
                if (userInfo.isManagedProfile()) {
                    // If userInfo.id is a managed profile, we also need to look at
                    // the policies set on the parent.
                    final DevicePolicyManager parentDpm = dpm.getParentProfileInstance(userInfo);
                    if (parentDpm.getMaximumTimeToLock(admin, userInfo.id) > 0) {
                        if (enforcedAdmin == null) {
                            enforcedAdmin = new EnforcedAdmin(admin, userInfo.id);
                        } else {
                            return EnforcedAdmin.MULTIPLE_ENFORCED_ADMIN;
                        }
                    }
                }
            }
        }
        return enforcedAdmin;
    }

    public static EnforcedAdmin getProfileOrDeviceOwner(Context context, int userId) {
        if (userId == UserHandle.USER_NULL) {
            return null;
        }
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        ComponentName adminComponent = dpm.getProfileOwnerAsUser(userId);
        if (adminComponent != null) {
            return new EnforcedAdmin(adminComponent, userId);
        }
        if (dpm.getDeviceOwnerUserId() == userId) {
            adminComponent = dpm.getDeviceOwnerComponentOnAnyUser();
            if (adminComponent != null) {
                return new EnforcedAdmin(adminComponent, userId);
            }
        }
        return null;
    }

    public static EnforcedAdmin getDeviceOwner(Context context) {
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        ComponentName adminComponent = dpm.getDeviceOwnerComponentOnAnyUser();
        if (adminComponent != null) {
            return new EnforcedAdmin(adminComponent, dpm.getDeviceOwnerUserId());
        }
        return null;
    }

    private static EnforcedAdmin getProfileOwner(Context context, int userId) {
        if (userId == UserHandle.USER_NULL) {
            return null;
        }
        final DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        if (dpm == null) {
            return null;
        }
        ComponentName adminComponent = dpm.getProfileOwnerAsUser(userId);
        if (adminComponent != null) {
            return new EnforcedAdmin(adminComponent, userId);
        }
        return null;
    }

    /**
     * Set the menu item as disabled by admin by adding a restricted padlock at the end of the
     * text and set the click listener which will send an intent to show the admin support details
     * dialog. If the admin is null, remove the padlock and disabled color span. When the admin is
     * null, we also set the OnMenuItemClickListener to null, so if you want to set a custom
     * OnMenuItemClickListener, set it after calling this method.
     */
    public static void setMenuItemAsDisabledByAdmin(final Context context,
            final MenuItem item, final EnforcedAdmin admin) {
        SpannableStringBuilder sb = new SpannableStringBuilder(item.getTitle());
        removeExistingRestrictedSpans(sb);

        if (admin != null) {
            final int disabledColor = context.getColor(R.color.disabled_text_color);
            sb.setSpan(new ForegroundColorSpan(disabledColor), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ImageSpan image = new RestrictedLockImageSpan(context);
            sb.append(" ", image, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                @Override
                public boolean onMenuItemClick(MenuItem item) {
                    sendShowAdminSupportDetailsIntent(context, admin);
                    return true;
                }
            });
        } else {
            item.setOnMenuItemClickListener(null);
        }
        item.setTitle(sb);
    }

    private static void removeExistingRestrictedSpans(SpannableStringBuilder sb) {
        final int length = sb.length();
        RestrictedLockImageSpan[] imageSpans = sb.getSpans(length - 1, length,
                RestrictedLockImageSpan.class);
        for (ImageSpan span : imageSpans) {
            final int start = sb.getSpanStart(span);
            final int end = sb.getSpanEnd(span);
            sb.removeSpan(span);
            sb.delete(start, end);
        }
        ForegroundColorSpan[] colorSpans = sb.getSpans(0, length, ForegroundColorSpan.class);
        for (ForegroundColorSpan span : colorSpans) {
            sb.removeSpan(span);
        }
    }

    /**
     * Send the intent to trigger the {@link android.settings.ShowAdminSupportDetailsDialog}.
     */
    public static void sendShowAdminSupportDetailsIntent(Context context, EnforcedAdmin admin) {
        final Intent intent = getShowAdminSupportDetailsIntent(context, admin);
        int targetUserId = UserHandle.myUserId();
        if (admin != null && admin.userId != UserHandle.USER_NULL
                && isCurrentUserOrProfile(context, admin.userId)) {
            targetUserId = admin.userId;
        }
        context.startActivityAsUser(intent, new UserHandle(targetUserId));
    }

    public static Intent getShowAdminSupportDetailsIntent(Context context, EnforcedAdmin admin) {
        final Intent intent = new Intent(Settings.ACTION_SHOW_ADMIN_SUPPORT_DETAILS);
        if (admin != null) {
            if (admin.component != null) {
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin.component);
            }
            int adminUserId = UserHandle.myUserId();
            if (admin.userId != UserHandle.USER_NULL) {
                adminUserId = admin.userId;
            }
            intent.putExtra(Intent.EXTRA_USER_ID, adminUserId);
        }
        return intent;
    }

    public static boolean isCurrentUserOrProfile(Context context, int userId) {
        UserManager um = UserManager.get(context);
        for (UserInfo userInfo : um.getProfiles(UserHandle.myUserId())) {
            if (userInfo.id == userId) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAdminInCurrentUserOrProfile(Context context, ComponentName admin) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        UserManager um = UserManager.get(context);
        for (UserInfo userInfo : um.getProfiles(UserHandle.myUserId())) {
            if (dpm.isAdminActiveAsUser(admin, userInfo.id)) {
                return true;
            }
        }
        return false;
    }

    public static void setTextViewPadlock(Context context,
            TextView textView, boolean showPadlock) {
        final SpannableStringBuilder sb = new SpannableStringBuilder(textView.getText());
        removeExistingRestrictedSpans(sb);
        if (showPadlock) {
            final ImageSpan image = new RestrictedLockImageSpan(context);
            sb.append(" ", image, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        textView.setText(sb);
    }

    /**
     * Takes a {@link android.widget.TextView} and applies an alpha so that the text looks like
     * disabled and appends a padlock to the text. This assumes that there are no
     * ForegroundColorSpans and RestrictedLockImageSpans used on the TextView.
     */
    public static void setTextViewAsDisabledByAdmin(Context context,
            TextView textView, boolean disabled) {
        final SpannableStringBuilder sb = new SpannableStringBuilder(textView.getText());
        removeExistingRestrictedSpans(sb);
        if (disabled) {
            final int disabledColor = context.getColor(R.color.disabled_text_color);
            sb.setSpan(new ForegroundColorSpan(disabledColor), 0, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            textView.setCompoundDrawables(null, null, getRestrictedPadlock(context), null);
            textView.setCompoundDrawablePadding(context.getResources().getDimensionPixelSize(
                    R.dimen.restricted_icon_padding));
        } else {
            textView.setCompoundDrawables(null, null, null, null);
        }
        textView.setText(sb);
    }

    public static class EnforcedAdmin {
        public ComponentName component = null;
        public int userId = UserHandle.USER_NULL;

        // We use this to represent the case where a policy is enforced by multiple admins.
        public final static EnforcedAdmin MULTIPLE_ENFORCED_ADMIN = new EnforcedAdmin();

        public EnforcedAdmin(ComponentName component, int userId) {
            this.component = component;
            this.userId = userId;
        }

        public EnforcedAdmin(EnforcedAdmin other) {
            if (other == null) {
                throw new IllegalArgumentException();
            }
            this.component = other.component;
            this.userId = other.userId;
        }

        public EnforcedAdmin() {}

        @Override
        public boolean equals(Object object) {
            if (object == this) return true;
            if (!(object instanceof EnforcedAdmin)) return false;
            EnforcedAdmin other = (EnforcedAdmin) object;
            if (userId != other.userId) {
                return false;
            }
            if ((component == null && other.component == null) ||
                    (component != null && component.equals(other.component))) {
                return true;
            }
            return false;
        }

        @Override
        public String toString() {
            return "EnforcedAdmin{component=" + component + ",userId=" + userId + "}";
        }

        public void copyTo(EnforcedAdmin other) {
            if (other == null) {
                throw new IllegalArgumentException();
            }
            other.component = component;
            other.userId = userId;
        }
    }
}
