/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.studio.shade.statusbar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActivityManager;
import android.app.ActivityManager.StackId;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.TaskStackBuilder;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.widget.LockPatternUtils;
import com.studio.shade.DejankUtils;
import com.studio.shade.Interpolators;
import com.studio.shade.R;
import com.studio.shade.SwipeHelper;
import com.studio.shade.SystemUI;
import com.studio.shade.assist.AssistManager;
import com.studio.shade.statusbar.NotificationData.Entry;
import com.studio.shade.statusbar.NotificationGuts.OnGutsClosedListener;
import com.studio.shade.statusbar.phone.NotificationGroupManager;
import com.studio.shade.statusbar.policy.HeadsUpManager;
import com.studio.shade.statusbar.policy.PreviewInflater;
import com.studio.shade.statusbar.policy.RemoteInputView;
import com.studio.shade.statusbar.stack.NotificationStackScrollLayout;
import com.studio.shade.statusbar.stack.StackStateAnimator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static android.service.notification.NotificationListenerService.Ranking.IMPORTANCE_HIGH;

public abstract class BaseStatusBar extends SystemUI implements
        CommandQueue.Callbacks, ActivatableNotificationView.OnActivatedListener,
        ExpandableNotificationRow.ExpansionLogger, NotificationData.Environment,
        ExpandableNotificationRow.OnExpandClickListener,
        OnGutsClosedListener {
    public static final String TAG = "StatusBar";
    public static final boolean DEBUG = false;
    public static final boolean MULTIUSER_DEBUG = false;

    public static final boolean ENABLE_REMOTE_INPUT =
            SystemProperties.getBoolean("debug.enable_remote_input", true);
    public static final boolean ENABLE_CHILD_NOTIFICATIONS
            = SystemProperties.getBoolean("debug.child_notifs", true);
    public static final boolean FORCE_REMOTE_INPUT_HISTORY =
            SystemProperties.getBoolean("debug.force_remoteinput_history", false);
    private static boolean ENABLE_LOCK_SCREEN_ALLOW_REMOTE_INPUT = false;

    protected static final int MSG_SHOW_RECENT_APPS = 1019;
    protected static final int MSG_HIDE_RECENT_APPS = 1020;
    protected static final int MSG_TOGGLE_RECENTS_APPS = 1021;
    protected static final int MSG_PRELOAD_RECENT_APPS = 1022;
    protected static final int MSG_CANCEL_PRELOAD_RECENT_APPS = 1023;
    protected static final int MSG_SHOW_NEXT_AFFILIATED_TASK = 1024;
    protected static final int MSG_SHOW_PREV_AFFILIATED_TASK = 1025;
    protected static final int MSG_TOGGLE_KEYBOARD_SHORTCUTS_MENU = 1026;
    protected static final int MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU = 1027;

    protected static final boolean ENABLE_HEADS_UP = true;
    protected static final String SETTING_HEADS_UP_TICKER = "ticker_gets_heads_up";

    private static final String PERMISSION_SELF = "com.studio.shade.permission.SELF";

    // Should match the values in PhoneWindowManager
    public static final String SYSTEM_DIALOG_REASON_RECENT_APPS = "recentapps";
    public static final String SYSTEM_DIALOG_REASON_HOME_KEY = "homekey";

    private static final String BANNER_ACTION_CANCEL =
            "com.studio.shade.statusbar.banner_action_cancel";
    private static final String BANNER_ACTION_SETUP =
            "com.studio.shade.statusbar.banner_action_setup";
    private static final String WORK_CHALLENGE_UNLOCKED_NOTIFICATION_ACTION
            = "com.studio.shade.statusbar.work_challenge_unlocked_notification_action";

    protected CommandQueue mCommandQueue;
    protected IStatusBarService mBarService;
    protected H mHandler = createHandler();

    // all notifications
    protected NotificationData mNotificationData;
    protected NotificationStackScrollLayout mStackScroller;

    protected NotificationGroupManager mGroupManager = new NotificationGroupManager();

    protected RemoteInputController mRemoteInputController;

    // for heads up notifications
    protected HeadsUpManager mHeadsUpManager;

    protected int mCurrentUserId = 0;
    final protected SparseArray<UserInfo> mCurrentProfiles = new SparseArray<UserInfo>();

    protected int mLayoutDirection = -1; // invalid
    protected AccessibilityManager mAccessibilityManager;

    protected boolean mDeviceInteractive;

    protected boolean mVisible;
    protected ArraySet<Entry> mHeadsUpEntriesToRemoveOnSwitch = new ArraySet<>();
    protected ArraySet<Entry> mRemoteInputEntriesToRemoveOnCollapse = new ArraySet<>();

    /**
     * Notifications with keys in this set are not actually around anymore. We kept them around
     * when they were canceled in response to a remote input interaction. This allows us to show
     * what you replied and allows you to continue typing into it.
     */
    protected ArraySet<String> mKeysKeptForRemoteInput = new ArraySet<>();
    private boolean mVisibleToUser;

    private Locale mLocale;
    private float mFontScale;

    protected boolean mUseHeadsUp = false;
    protected boolean mHeadsUpTicker = false;
    protected boolean mDisableNotificationAlerts = false;

    protected DevicePolicyManager mDevicePolicyManager;
    protected IDreamManager mDreamManager;
    protected PowerManager mPowerManager;

    // public mode, private notifications, etc
    private final SparseBooleanArray mUsersAllowingPrivateNotifications = new SparseBooleanArray();
    private final SparseBooleanArray mUsersAllowingNotifications = new SparseBooleanArray();

    private UserManager mUserManager;
    private int mDensity;

    // UI-specific methods

    /**
     * Create all windows necessary for the status bar (including navigation, overlay panels, etc)
     * and add them to the window manager.
     */
    protected abstract void createAndAddWindows();

    protected WindowManager mWindowManager;
    protected IWindowManager mWindowManagerService;

    protected abstract void refreshLayout(int layoutDirection);

    protected Display mDisplay;

    private boolean mDeviceProvisioned = false;

    // which notification is currently being longpress-examined by the user
    private NotificationGuts mNotificationGutsExposed;

    /**
     * The {@link StatusBarState} of the status bar.
     */
    protected int mState;

    protected NotificationOverflowContainer mKeyguardIconOverflowContainer;
    protected DismissView mDismissView;
    protected EmptyShadeView mEmptyShadeView;

    private NotificationClicker mNotificationClicker = new NotificationClicker();

    protected AssistManager mAssistManager;

    protected boolean mVrMode;

    @Override  // NotificationData.Environment
    public boolean isDeviceProvisioned() {
        return mDeviceProvisioned;
    }

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mVrMode = enabled;
        }
    };

    public boolean isDeviceInVrMode() {
        return mVrMode;
    }

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean provisioned = 0 != Settings.Global.getInt(
                    mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED, 0);
            if (provisioned != mDeviceProvisioned) {
                mDeviceProvisioned = provisioned;
                updateNotifications();
            }
        }
    };

    private final ContentObserver mLockscreenSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            // We don't know which user changed LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS or
            // LOCK_SCREEN_SHOW_NOTIFICATIONS, so we just dump our cache ...
            mUsersAllowingPrivateNotifications.clear();
            mUsersAllowingNotifications.clear();
            // ... and refresh all the notifications
            updateNotifications();
        }
    };

    private RemoteViews.OnClickHandler mOnClickHandler = new RemoteViews.OnClickHandler() {
        @Override
        public boolean onClickHandler(
                final View view, final PendingIntent pendingIntent, final Intent fillInIntent) {
            if (handleRemoteInput(view, pendingIntent, fillInIntent)) {
                return true;
            }

            if (DEBUG) {
                Log.v(TAG, "Notification click handler invoked for intent: " + pendingIntent);
            }
            logActionClick(view);
            // The intent we are sending is for the application, which
            // won't have permission to immediately start an activity after
            // the user switches to home.  We know it is safe to do at this
            // point, so make sure new activity switches are now allowed.
            try {
                ActivityManagerNative.getDefault().resumeAppSwitches();
            } catch (RemoteException e) {
            }
            final boolean isActivity = pendingIntent.isActivity();
            if (isActivity) {
                return true;
            } else {
                return superOnClickHandler(view, pendingIntent, fillInIntent);
            }
        }

        private void logActionClick(View view) {
            ViewParent parent = view.getParent();
            String key = getNotificationKeyForParent(parent);
            if (key == null) {
                Log.w(TAG, "Couldn't determine notification for click.");
                return;
            }
            int index = -1;
            // If this is a default template, determine the index of the button.
            if (view.getId() == com.android.internal.R.id.action0 &&
                    parent != null && parent instanceof ViewGroup) {
                ViewGroup actionGroup = (ViewGroup) parent;
                index = actionGroup.indexOfChild(view);
            }
            try {
                mBarService.onNotificationActionClick(key, index);
            } catch (RemoteException e) {
                // Ignore
            }
        }

        private String getNotificationKeyForParent(ViewParent parent) {
            while (parent != null) {
                if (parent instanceof ExpandableNotificationRow) {
                    return ((ExpandableNotificationRow) parent).getStatusBarNotification().getKey();
                }
                parent = parent.getParent();
            }
            return null;
        }

        private boolean superOnClickHandler(View view, PendingIntent pendingIntent,
                Intent fillInIntent) {
            return super.onClickHandler(view, pendingIntent, fillInIntent,
                    StackId.FULLSCREEN_WORKSPACE_STACK_ID);
        }

        private boolean handleRemoteInput(View view, PendingIntent pendingIntent, Intent fillInIntent) {
            Object tag = view.getTag(com.android.internal.R.id.remote_input_tag);
            RemoteInput[] inputs = null;
            if (tag instanceof RemoteInput[]) {
                inputs = (RemoteInput[]) tag;
            }

            if (inputs == null) {
                return false;
            }

            RemoteInput input = null;

            for (RemoteInput i : inputs) {
                if (i.getAllowFreeFormInput()) {
                    input = i;
                }
            }

            if (input == null) {
                return false;
            }

            ViewParent p = view.getParent();
            RemoteInputView riv = null;
            while (p != null) {
                if (p instanceof View) {
                    View pv = (View) p;
                    if (pv.isRootNamespace()) {
                        riv = (RemoteInputView) pv.findViewWithTag(RemoteInputView.VIEW_TAG);
                        break;
                    }
                }
                p = p.getParent();
            }
            ExpandableNotificationRow row = null;
            while (p != null) {
                if (p instanceof ExpandableNotificationRow) {
                    row = (ExpandableNotificationRow) p;
                    break;
                }
                p = p.getParent();
            }

            if (riv == null || row == null) {
                return false;
            }

            row.setUserExpanded(true);

            riv.setVisibility(View.VISIBLE);
            int cx = view.getLeft() + view.getWidth() / 2;
            int cy = view.getTop() + view.getHeight() / 2;
            int w = riv.getWidth();
            int h = riv.getHeight();
            int r = Math.max(
                    Math.max(cx + cy, cx + (h - cy)),
                    Math.max((w - cx) + cy, (w - cx) + (h - cy)));
            ViewAnimationUtils.createCircularReveal(riv, cx, cy, 0, r)
                    .start();

            riv.setPendingIntent(pendingIntent);
            riv.setRemoteInput(inputs, input);
            riv.focus();

            return true;
        }

    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                mCurrentUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                updateCurrentProfilesCache();
                if (true) Log.v(TAG, "userId " + mCurrentUserId + " is in the house");

                userSwitched(mCurrentUserId);
            } else if (Intent.ACTION_USER_ADDED.equals(action)) {
                updateCurrentProfilesCache();
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                List<ActivityManager.RecentTaskInfo> recentTask = null;
                try {
                    recentTask = ActivityManagerNative.getDefault().getRecentTasks(1,
                            ActivityManager.RECENT_WITH_EXCLUDED
                            | ActivityManager.RECENT_INCLUDE_PROFILES,
                            mCurrentUserId).getList();
                } catch (RemoteException e) {
                    // Abandon hope activity manager not running.
                }
                if (recentTask != null && recentTask.size() > 0) {
                    UserInfo user = mUserManager.getUserInfo(recentTask.get(0).userId);
                    if (user != null && user.isManagedProfile()) {
                        Toast toast = Toast.makeText(mContext,
                                R.string.managed_profile_foreground_toast,
                                Toast.LENGTH_SHORT);
                        TextView text = (TextView) toast.getView().findViewById(
                                android.R.id.message);
                        text.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                R.drawable.stat_sys_managed_profile_status, 0, 0, 0);
                        int paddingPx = mContext.getResources().getDimensionPixelSize(
                                R.dimen.managed_profile_toast_padding);
                        text.setCompoundDrawablePadding(paddingPx);
                        toast.show();
                    }
                }
            } else if (BANNER_ACTION_CANCEL.equals(action) || BANNER_ACTION_SETUP.equals(action)) {
                NotificationManager noMan = (NotificationManager)
                        mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                noMan.cancel(R.id.notification_hidden);

                Settings.Secure.putInt(mContext.getContentResolver(),
                        Settings.Secure.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING, 0);
                if (BANNER_ACTION_SETUP.equals(action)) {
                    animateCollapsePanels(CommandQueue.FLAG_EXCLUDE_RECENTS_PANEL,
                            true /* force */);
                    mContext.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_REDACTION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    );
                }
            } else if (WORK_CHALLENGE_UNLOCKED_NOTIFICATION_ACTION.equals(action)) {
                final IntentSender intentSender = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                final String notificationKey = intent.getStringExtra(Intent.EXTRA_INDEX);
                if (intentSender != null) {
                    try {
                        mContext.startIntentSender(intentSender, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        /* ignore */
                    }
                }
                if (notificationKey != null) {
                    try {
                        mBarService.onNotificationClick(notificationKey);
                    } catch (RemoteException e) {
                        /* ignore */
                    }
                }
                onWorkChallengeUnlocked();
            }
        }
    };

    private final BroadcastReceiver mAllUsersReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED.equals(action) &&
                    isCurrentProfile(getSendingUserId())) {
                mUsersAllowingPrivateNotifications.clear();
                updateLockscreenNotificationSetting();
                updateNotifications();
            }
        }
    };

    private final NotificationListenerService mNotificationListener =
            new NotificationListenerService() {
        @Override
        public void onListenerConnected() {
            if (DEBUG) Log.d(TAG, "onListenerConnected");
            final StatusBarNotification[] notifications = getActiveNotifications();
            final RankingMap currentRanking = getCurrentRanking();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    for (StatusBarNotification sbn : notifications) {
                        addNotification(sbn, currentRanking, null /* oldEntry */);
                    }
                }
            });
        }

        @Override
        public void onNotificationPosted(final StatusBarNotification sbn,
                final RankingMap rankingMap) {
            if (DEBUG) Log.d(TAG, "onNotificationPosted: " + sbn);
            if (sbn != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        processForRemoteInput(sbn.getNotification());
                        String key = sbn.getKey();
                        mKeysKeptForRemoteInput.remove(key);
                        boolean isUpdate = mNotificationData.get(key) != null;
                        // In case we don't allow child notifications, we ignore children of
                        // notifications that have a summary, since we're not going to show them
                        // anyway. This is true also when the summary is canceled,
                        // because children are automatically canceled by NoMan in that case.
                        if (!ENABLE_CHILD_NOTIFICATIONS
                            && mGroupManager.isChildInGroupWithSummary(sbn)) {
                            if (DEBUG) {
                                Log.d(TAG, "Ignoring group child due to existing summary: " + sbn);
                            }

                            // Remove existing notification to avoid stale data.
                            if (isUpdate) {
                                removeNotification(key, rankingMap);
                            } else {
                                mNotificationData.updateRanking(rankingMap);
                            }
                            return;
                        }
                        if (isUpdate) {
                            updateNotification(sbn, rankingMap);
                        } else {
                            addNotification(sbn, rankingMap, null /* oldEntry */);
                        }
                    }
                });
            }
        }

        @Override
        public void onNotificationRemoved(StatusBarNotification sbn,
                final RankingMap rankingMap) {
            if (DEBUG) Log.d(TAG, "onNotificationRemoved: " + sbn);
            if (sbn != null) {
                final String key = sbn.getKey();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        removeNotification(key, rankingMap);
                    }
                });
            }
        }

        @Override
        public void onNotificationRankingUpdate(final RankingMap rankingMap) {
            if (DEBUG) Log.d(TAG, "onRankingUpdate");
            if (rankingMap != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    updateNotificationRanking(rankingMap);
                }
            });
        }                            }

    };

    private void updateCurrentProfilesCache() {
        synchronized (mCurrentProfiles) {
            mCurrentProfiles.clear();
            if (mUserManager != null) {
                for (UserInfo user : mUserManager.getProfiles(mCurrentUserId)) {
                    mCurrentProfiles.put(user.id, user);
                }
            }
        }
    }

    public void start() {
        mWindowManager = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        mWindowManagerService = WindowManagerGlobal.getWindowManagerService();
        mDisplay = mWindowManager.getDefaultDisplay();
        mDevicePolicyManager = (DevicePolicyManager)mContext.getSystemService(
                Context.DEVICE_POLICY_SERVICE);

        mNotificationData = new NotificationData(this);

        mAccessibilityManager = (AccessibilityManager)
                mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);

        mDreamManager = IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.DEVICE_PROVISIONED), true,
                mSettingsObserver);

        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        final Configuration currentConfig = mContext.getResources().getConfiguration();
        mLocale = currentConfig.locale;
        mLayoutDirection = TextUtils.getLayoutDirectionFromLocale(mLocale);
        mFontScale = currentConfig.fontScale;
        mDensity = currentConfig.densityDpi;

        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);

        // Connect in to the status bar manager service
        mCommandQueue = new CommandQueue(this);

        int[] switches = new int[9];
        ArrayList<IBinder> binders = new ArrayList<IBinder>();
        ArrayList<String> iconSlots = new ArrayList<>();
        ArrayList<StatusBarIcon> icons = new ArrayList<>();
        Rect fullscreenStackBounds = new Rect();
        Rect dockedStackBounds = new Rect();
        try {
            mBarService.registerStatusBar(mCommandQueue, iconSlots, icons, switches, binders,
                    fullscreenStackBounds, dockedStackBounds);
        } catch (RemoteException ex) {
            // If the system process isn't there we're doomed anyway.
        }

        createAndAddWindows();

        mSettingsObserver.onChange(false); // set up
        disable(switches[0], switches[6], false /* animate */);
        setSystemUiVisibility(switches[1], switches[7], switches[8], 0xffffffff,
                fullscreenStackBounds, dockedStackBounds);
        topAppWindowChanged(switches[2] != 0);
        // StatusBarManagerService has a back up of IME token and it's restored here.
        setImeWindowStatus(binders.get(0), switches[3], switches[4], switches[5] != 0);

        // Set up the initial icon state
        int N = iconSlots.size();
        int viewIndex = 0;
        for (int i=0; i < N; i++) {
            setIcon(iconSlots.get(i), icons.get(i));
        }

        // Set up the initial notification state.
        try {
            mNotificationListener.registerAsSystemService(mContext,
                    new ComponentName(mContext.getPackageName(), getClass().getCanonicalName()),
                    UserHandle.USER_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to register notification listener", e);
        }


        if (DEBUG) {
            Log.d(TAG, String.format(
                    "init: icons=%d disabled=0x%08x lights=0x%08x menu=0x%08x imeButton=0x%08x",
                   icons.size(),
                   switches[0],
                   switches[1],
                   switches[2],
                   switches[3]
                   ));
        }

        mCurrentUserId = ActivityManager.getCurrentUser();
        setHeadsUpUser(mCurrentUserId);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(mBroadcastReceiver, filter);

        IntentFilter internalFilter = new IntentFilter();
        internalFilter.addAction(WORK_CHALLENGE_UNLOCKED_NOTIFICATION_ACTION);
        internalFilter.addAction(BANNER_ACTION_CANCEL);
        internalFilter.addAction(BANNER_ACTION_SETUP);
        mContext.registerReceiver(mBroadcastReceiver, internalFilter, PERMISSION_SELF, null);

        IntentFilter allUsersFilter = new IntentFilter();
        allUsersFilter.addAction(
                DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mContext.registerReceiverAsUser(mAllUsersReceiver, UserHandle.ALL, allUsersFilter,
                null, null);
        updateCurrentProfilesCache();

        IVrManager vrManager = IVrManager.Stub.asInterface(ServiceManager.getService("vrmanager"));
        try {
            vrManager.registerListener(mVrStateCallbacks);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to register VR mode state listener: " + e);
        }

    }

    protected void notifyUserAboutHiddenNotifications() {
        if (0 != Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.SHOW_NOTE_ABOUT_NOTIFICATION_HIDING, 1)) {
            Log.d(TAG, "user hasn't seen notification about hidden notifications");
            final String packageName = mContext.getPackageName();
            PendingIntent cancelIntent = PendingIntent.getBroadcast(mContext, 0,
                    new Intent(BANNER_ACTION_CANCEL).setPackage(packageName),
                    PendingIntent.FLAG_CANCEL_CURRENT);
            PendingIntent setupIntent = PendingIntent.getBroadcast(mContext, 0,
                    new Intent(BANNER_ACTION_SETUP).setPackage(packageName),
                    PendingIntent.FLAG_CANCEL_CURRENT);

            final int colorRes = com.android.internal.R.color.system_notification_accent_color;
            Notification.Builder note = new Notification.Builder(mContext)
                    .setSmallIcon(R.drawable.ic_android)
                    .setContentTitle(mContext.getString(R.string.hidden_notifications_title))
                    .setContentText(mContext.getString(R.string.hidden_notifications_text))
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setOngoing(true)
                    .setColor(mContext.getColor(colorRes))
                    .setContentIntent(setupIntent)
                    .addAction(R.drawable.ic_close,
                            mContext.getString(R.string.hidden_notifications_cancel),
                            cancelIntent)
                    .addAction(R.drawable.ic_settings,
                            mContext.getString(R.string.hidden_notifications_setup),
                            setupIntent);
            overrideNotificationAppName(mContext, note);

            NotificationManager noMan =
                    (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            noMan.notify(R.id.notification_hidden, note.build());
        }
    }

    public void userSwitched(int newUserId) {
        setHeadsUpUser(newUserId);
    }

    protected abstract void setHeadsUpUser(int newUserId);

    @Override  // NotificationData.Environment
    public boolean isNotificationForCurrentProfiles(StatusBarNotification n) {
        final int thisUserId = mCurrentUserId;
        final int notificationUserId = n.getUserId();
        if (DEBUG && MULTIUSER_DEBUG) {
            Log.v(TAG, String.format("%s: current userid: %d, notification userid: %d",
                    n, thisUserId, notificationUserId));
        }
        return isCurrentProfile(notificationUserId);
    }

    protected void setNotificationShown(StatusBarNotification n) {
        setNotificationsShown(new String[]{n.getKey()});
    }

    protected void setNotificationsShown(String[] keys) {
        try {
            mNotificationListener.setNotificationsShown(keys);
        } catch (RuntimeException e) {
            Log.d(TAG, "failed setNotificationsShown: ", e);
        }
    }

    protected boolean isCurrentProfile(int userId) {
        synchronized (mCurrentProfiles) {
            return userId == UserHandle.USER_ALL || mCurrentProfiles.get(userId) != null;
        }
    }

    @Override
    public String getCurrentMediaNotificationKey() {
        return null;
    }

    @Override
    public NotificationGroupManager getGroupManager() {
        return mGroupManager;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        final Locale locale = mContext.getResources().getConfiguration().locale;
        final int ld = TextUtils.getLayoutDirectionFromLocale(locale);
        final float fontScale = newConfig.fontScale;
        final int density = newConfig.densityDpi;
        if (density != mDensity || mFontScale != fontScale) {
            onDensityOrFontScaleChanged();
            mDensity = density;
            mFontScale = fontScale;
        }
        if (! locale.equals(mLocale) || ld != mLayoutDirection) {
            if (DEBUG) {
                Log.v(TAG, String.format(
                        "config changed locale/LD: %s (%d) -> %s (%d)", mLocale, mLayoutDirection,
                        locale, ld));
            }
            mLocale = locale;
            mLayoutDirection = ld;
            refreshLayout(ld);
        }
    }

    protected void onDensityOrFontScaleChanged() {
        ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
        for (int i = 0; i < activeNotifications.size(); i++) {
            Entry entry = activeNotifications.get(i);
            boolean exposedGuts = entry.row.getGuts() == mNotificationGutsExposed;
            entry.row.reInflateViews();
            if (exposedGuts) {
                mNotificationGutsExposed = entry.row.getGuts();
                bindGuts(entry.row);
            }
            entry.cacheContentViews(mContext, null /* updatedNotification */);
            inflateViews(entry, mStackScroller);
        }
    }

    protected View bindVetoButtonClickListener(View row, final StatusBarNotification n) {
        View vetoButton = row.findViewById(R.id.veto);
        vetoButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // Accessibility feedback
                v.announceForAccessibility(
                        mContext.getString(R.string.accessibility_notification_dismissed));
                performRemoveNotification(n, false /* removeView */);
            }
        });
        vetoButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return vetoButton;
    }

    protected void performRemoveNotification(StatusBarNotification n, boolean removeView) {
        final String pkg = n.getPackageName();
        final String tag = n.getTag();
        final int id = n.getId();
        final int userId = n.getUserId();
        try {
            mBarService.onNotificationClear(pkg, tag, id, userId);
            if (FORCE_REMOTE_INPUT_HISTORY
                    && mKeysKeptForRemoteInput.contains(n.getKey())) {
                mKeysKeptForRemoteInput.remove(n.getKey());
                removeView = true;
            }
            if (mRemoteInputEntriesToRemoveOnCollapse.remove(mNotificationData.get(n.getKey()))) {
                removeView = true;
            }
            if (removeView) {
                removeNotification(n.getKey(), null);
            }

        } catch (RemoteException ex) {
            // system process is dead if we're here.
        }
    }


    protected void applyColorsAndBackgrounds(StatusBarNotification sbn,
            NotificationData.Entry entry) {

        if (entry.getContentView().getId()
                != com.android.internal.R.id.status_bar_latest_event_content) {
            // Using custom RemoteViews
            if (entry.targetSdk >= Build.VERSION_CODES.GINGERBREAD
                    && entry.targetSdk < Build.VERSION_CODES.LOLLIPOP) {
                entry.row.setShowingLegacyBackground(true);
                entry.legacy = true;
            }
        }

        if (entry.icon != null) {
            entry.icon.setTag(R.id.icon_is_pre_L, entry.targetSdk < Build.VERSION_CODES.LOLLIPOP);
        }
    }

    public boolean isMediaNotification(NotificationData.Entry entry) {
        // TODO: confirm that there's a valid media key
        return entry.getExpandedContentView() != null &&
               entry.getExpandedContentView()
                       .findViewById(com.android.internal.R.id.media_actions) != null;
    }

    // The (i) button in the guts that links to the system notification settings for that app
    private void startAppNotificationSettingsActivity(String packageName, final int appUid) {
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);
        startNotificationGutsIntent(intent, appUid);
    }

    private void startNotificationGutsIntent(final Intent intent, final int appUid) {
    }

    private void bindGuts(final ExpandableNotificationRow row) {
        row.inflateGuts();
        final StatusBarNotification sbn = row.getStatusBarNotification();
        PackageManager pmUser = getPackageManagerForUser(mContext, sbn.getUser().getIdentifier());
        row.setTag(sbn.getPackageName());
        final NotificationGuts guts = row.getGuts();
        guts.setClosedListener(this);
        final String pkg = sbn.getPackageName();
        String appname = pkg;
        Drawable pkgicon = null;
        int appUid = -1;
        try {
            final ApplicationInfo info = pmUser.getApplicationInfo(pkg,
                    PackageManager.GET_UNINSTALLED_PACKAGES
                            | PackageManager.GET_DISABLED_COMPONENTS);
            if (info != null) {
                appname = String.valueOf(pmUser.getApplicationLabel(info));
                pkgicon = pmUser.getApplicationIcon(info);
                appUid = info.uid;
            }
        } catch (NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = pmUser.getDefaultActivityIcon();
        }

        ((ImageView) guts.findViewById(R.id.app_icon)).setImageDrawable(pkgicon);
        ((TextView) guts.findViewById(R.id.pkgname)).setText(appname);

        final TextView settingsButton = (TextView) guts.findViewById(R.id.more_settings);
        if (appUid >= 0) {
            final int appUidF = appUid;
            settingsButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    MetricsLogger.action(mContext, MetricsEvent.ACTION_NOTE_INFO);
                    guts.resetFalsingCheck();
                    startAppNotificationSettingsActivity(pkg, appUidF);
                }
            });
            settingsButton.setText(R.string.notification_more_settings);
        } else {
            settingsButton.setVisibility(View.GONE);
        }

        guts.bindImportance(pmUser, sbn, mNotificationData.getImportance(sbn.getKey()));

        final TextView doneButton = (TextView) guts.findViewById(R.id.done);
        doneButton.setText(R.string.notification_done);
        doneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // If the user has security enabled, show challenge if the setting is changed.
                if (guts.hasImportanceChanged() &&
                        (mState == StatusBarState.KEYGUARD
                        || mState == StatusBarState.SHADE_LOCKED)) {
                } else {
                    saveImportanceCloseControls(sbn, row, guts, v);
                }
            }
        });
    }

    private void saveImportanceCloseControls(StatusBarNotification sbn,
            ExpandableNotificationRow row, NotificationGuts guts, View done) {
        guts.resetFalsingCheck();
        guts.saveImportance(sbn);

        int[] rowLocation = new int[2];
        int[] doneLocation = new int[2];
        row.getLocationOnScreen(rowLocation);
        done.getLocationOnScreen(doneLocation);

        final int centerX = done.getWidth() / 2;
        final int centerY = done.getHeight() / 2;
        final int x = doneLocation[0] - rowLocation[0] + centerX;
        final int y = doneLocation[1] - rowLocation[1] + centerY;
        dismissPopups(x, y);
    }

    protected SwipeHelper.LongPressListener getNotificationLongClicker() {
        return new SwipeHelper.LongPressListener() {
            @Override
            public boolean onLongPress(View v, final int x, final int y) {
                if (!(v instanceof ExpandableNotificationRow)) {
                    return false;
                }
                if (v.getWindowToken() == null) {
                    Log.e(TAG, "Trying to show notification guts, but not attached to window");
                    return false;
                }

                final ExpandableNotificationRow row = (ExpandableNotificationRow) v;
                bindGuts(row);

                // Assume we are a status_bar_notification_row
                final NotificationGuts guts = row.getGuts();
                if (guts == null) {
                    // This view has no guts. Examples are the more card or the dismiss all view
                    return false;
                }

                // Already showing?
                if (guts.getVisibility() == View.VISIBLE) {
                    dismissPopups(x, y);
                    return false;
                }

                MetricsLogger.action(mContext, MetricsEvent.ACTION_NOTE_CONTROLS);

                // ensure that it's laid but not visible until actually laid out
                guts.setVisibility(View.INVISIBLE);
                // Post to ensure the the guts are properly laid out.
                guts.post(new Runnable() {
                    public void run() {
                        dismissPopups(-1 /* x */, -1 /* y */, false /* resetGear */,
                                false /* animate */);
                        guts.setVisibility(View.VISIBLE);
                        final double horz = Math.max(guts.getWidth() - x, x);
                        final double vert = Math.max(guts.getHeight() - y, y);
                        final float r = (float) Math.hypot(horz, vert);
                        final Animator a
                                = ViewAnimationUtils.createCircularReveal(guts, x, y, 0, r);
                        a.setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);
                        a.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
                        a.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                super.onAnimationEnd(animation);
                                // Move the notification view back over the gear
                                row.resetTranslation();
                            }
                        });
                        a.start();
                        guts.setExposed(true /* exposed */,
                                mState == StatusBarState.KEYGUARD /* needsFalsingProtection */);
                        row.closeRemoteInput();
                        mStackScroller.onHeightChanged(null, true /* needsAnimation */);
                        mNotificationGutsExposed = guts;
                    }
                });
                return true;
            }
        };
    }

    /**
     * Returns the exposed NotificationGuts or null if none are exposed.
     */
    public NotificationGuts getExposedGuts() {
        return mNotificationGutsExposed;
    }

    public void dismissPopups() {
        dismissPopups(-1 /* x */, -1 /* y */, true /* resetGear */, false /* animate */);
    }

    private void dismissPopups(int x, int y) {
        dismissPopups(x, y, true /* resetGear */, false /* animate */);
    }

    public void dismissPopups(int x, int y, boolean resetGear, boolean animate) {
        if (mNotificationGutsExposed != null) {
            mNotificationGutsExposed.closeControls(x, y, true /* notify */);
        }
        if (resetGear) {
            mStackScroller.resetExposedGearView(animate, true /* force */);
        }
    }

    @Override
    public void onGutsClosed(NotificationGuts guts) {
        mStackScroller.onHeightChanged(null, true /* needsAnimation */);
        mNotificationGutsExposed = null;
    }

    @Override
    public void showRecentApps(boolean triggeredFromAltTab, boolean fromHome) {
        int msg = MSG_SHOW_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.obtainMessage(msg, triggeredFromAltTab ? 1 : 0, fromHome ? 1 : 0).sendToTarget();
    }

    @Override
    public void hideRecentApps(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        int msg = MSG_HIDE_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.obtainMessage(msg, triggeredFromAltTab ? 1 : 0,
                triggeredFromHomeKey ? 1 : 0).sendToTarget();
    }

    @Override
    public void toggleRecentApps() {
    }

    @Override
    public void toggleSplitScreen() {
    }

    @Override
    public void preloadRecentApps() {
        int msg = MSG_PRELOAD_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void cancelPreloadRecentApps() {
        int msg = MSG_CANCEL_PRELOAD_RECENT_APPS;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void dismissKeyboardShortcutsMenu() {
        int msg = MSG_DISMISS_KEYBOARD_SHORTCUTS_MENU;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    @Override
    public void toggleKeyboardShortcutsMenu(int deviceId) {
        int msg = MSG_TOGGLE_KEYBOARD_SHORTCUTS_MENU;
        mHandler.removeMessages(msg);
        mHandler.obtainMessage(msg, deviceId, 0).sendToTarget();
    }

    /** Jumps to the next affiliated task in the group. */
    public void showNextAffiliatedTask() {
        int msg = MSG_SHOW_NEXT_AFFILIATED_TASK;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    /** Jumps to the previous affiliated task in the group. */
    public void showPreviousAffiliatedTask() {
        int msg = MSG_SHOW_PREV_AFFILIATED_TASK;
        mHandler.removeMessages(msg);
        mHandler.sendEmptyMessage(msg);
    }

    protected H createHandler() {
         return new H();
    }

    protected void sendCloseSystemWindows(String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

    protected abstract View getStatusBarView();

    protected View.OnTouchListener mRecentsPreloadOnTouchListener = new View.OnTouchListener() {
        // additional optimization when we have software system buttons - start loading the recent
        // tasks on touch down
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getAction() & MotionEvent.ACTION_MASK;
            if (action == MotionEvent.ACTION_DOWN) {
            } else if (action == MotionEvent.ACTION_CANCEL) {
            } else if (action == MotionEvent.ACTION_UP) {
                if (!v.isPressed()) {
                }

            }
            return false;
        }
    };

    protected void toggleKeyboardShortcuts(int deviceId) {
    }

    protected void dismissKeyboardShortcuts() {
    }

    protected void cancelPreloadingRecents() {
    }

    /**
     * If there is an active heads-up notification and it has a fullscreen intent, fire it now.
     */
    public abstract void maybeEscalateHeadsUp();

    protected void onWorkChallengeUnlocked() {}

    /**
     * Has the given user chosen to allow notifications to be shown even when the lockscreen is in
     * "public" (secure & locked) mode?
     */
    public boolean userAllowsNotificationsInPublic(int userHandle) {
        if (userHandle == UserHandle.USER_ALL) {
            return true;
        }

        if (mUsersAllowingNotifications.indexOfKey(userHandle) < 0) {
            final boolean allowed = 0 != Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS, 0, userHandle);
            mUsersAllowingNotifications.append(userHandle, allowed);
            return allowed;
        }

        return mUsersAllowingNotifications.get(userHandle);
    }

    /**
     * Has the given user chosen to allow their private (full) notifications to be shown even
     * when the lockscreen is in "public" (secure & locked) mode?
     */
    public boolean userAllowsPrivateNotificationsInPublic(int userHandle) {
        if (userHandle == UserHandle.USER_ALL) {
            return true;
        }

        if (mUsersAllowingPrivateNotifications.indexOfKey(userHandle) < 0) {
            final boolean allowedByUser = 0 != Settings.Secure.getIntForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS, 0, userHandle);
            final boolean allowedByDpm = adminAllowsUnredactedNotifications(userHandle);
            final boolean allowed = allowedByUser && allowedByDpm;
            mUsersAllowingPrivateNotifications.append(userHandle, allowed);
            return allowed;
        }

        return mUsersAllowingPrivateNotifications.get(userHandle);
    }

    private boolean adminAllowsUnredactedNotifications(int userHandle) {
        if (userHandle == UserHandle.USER_ALL) {
            return true;
        }
        final int dpmFlags = mDevicePolicyManager.getKeyguardDisabledFeatures(null /* admin */,
                    userHandle);
        return (dpmFlags & DevicePolicyManager.KEYGUARD_DISABLE_UNREDACTED_NOTIFICATIONS) == 0;
    }

    /**
     * Returns true if we're on a secure lockscreen and the user wants to hide notification data.
     * If so, notifications should be hidden.
     */
    @Override  // NotificationData.Environment
    public boolean shouldHideNotifications(int userid) {
        return !userAllowsNotificationsInPublic(userid);
    }

    /**
     * Returns true if we're on a secure lockscreen and the user wants to hide notifications via
     * package-specific override.
     */
    @Override // NotificationDate.Environment
    public boolean shouldHideNotifications(String key) {
        return mNotificationData.getVisibilityOverride(key) == Notification.VISIBILITY_SECRET;
    }

    public void onNotificationClear(StatusBarNotification notification) {
        try {
            mBarService.onNotificationClear(
                    notification.getPackageName(),
                    notification.getTag(),
                    notification.getId(),
                    notification.getUserId());
        } catch (android.os.RemoteException ex) {
            // oh well
        }
    }

    /**
     * Called when the notification panel layouts
     */
    public void onPanelLaidOut() {
        if (mState == StatusBarState.KEYGUARD) {
                mStackScroller.getChildCount();
        }
    }

    protected void onLockedRemoteInput(ExpandableNotificationRow row, View clickedView) {}

    protected void onLockedWorkRemoteInput(int userId, ExpandableNotificationRow row,
            View clicked) {}

    @Override
    public void onExpandClicked(Entry clickedEntry, boolean nowExpanded) {
    }

    protected class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
             case MSG_SHOW_NEXT_AFFILIATED_TASK:
                  showRecentsNextAffiliatedTask();
                  break;
             case MSG_SHOW_PREV_AFFILIATED_TASK:
                  showRecentsPreviousAffiliatedTask();
                  break;
             case MSG_TOGGLE_KEYBOARD_SHORTCUTS_MENU:
                  toggleKeyboardShortcuts(m.arg1);
                  break;
            }
        }
    }

    protected void workAroundBadLayerDrawableOpacity(View v) {
    }

    protected boolean inflateViews(Entry entry, ViewGroup parent) {
        PackageManager pmUser = getPackageManagerForUser(mContext,
                entry.notification.getUser().getIdentifier());

        final StatusBarNotification sbn = entry.notification;
        entry.cacheContentViews(mContext, null);

        final RemoteViews contentView = entry.cachedContentView;
        final RemoteViews bigContentView = entry.cachedBigContentView;
        final RemoteViews headsUpContentView = entry.cachedHeadsUpContentView;
        final RemoteViews publicContentView = entry.cachedPublicContentView;

        if (contentView == null) {
            Log.v(TAG, "no contentView for: " + sbn.getNotification());
            return false;
        }

        if (DEBUG) {
            Log.v(TAG, "publicContentView: " + publicContentView);
        }

        ExpandableNotificationRow row;

        // Stash away previous user expansion state so we can restore it at
        // the end.
        boolean hasUserChangedExpansion = false;
        boolean userExpanded = false;
        boolean userLocked = false;

        if (entry.row != null) {
            row = entry.row;
            hasUserChangedExpansion = row.hasUserChangedExpansion();
            userExpanded = row.isUserExpanded();
            userLocked = row.isUserLocked();
            entry.reset();
            if (hasUserChangedExpansion) {
                row.setUserExpanded(userExpanded);
            }
        } else {
            // create the row view
            LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            row = (ExpandableNotificationRow) inflater.inflate(R.layout.status_bar_notification_row,
                    parent, false);
            row.setExpansionLogger(this, entry.notification.getKey());
            row.setGroupManager(mGroupManager);
            row.setHeadsUpManager(mHeadsUpManager);
            row.setRemoteInputController(mRemoteInputController);
            row.setOnExpandClickListener(this);

            // Get the app name.
            // Note that Notification.Builder#bindHeaderAppName has similar logic
            // but since this field is used in the guts, it must be accurate.
            // Therefore we will only show the application label, or, failing that, the
            // package name. No substitutions.
            final String pkg = sbn.getPackageName();
            String appname = pkg;
            try {
                final ApplicationInfo info = pmUser.getApplicationInfo(pkg,
                        PackageManager.GET_UNINSTALLED_PACKAGES
                                | PackageManager.GET_DISABLED_COMPONENTS);
                if (info != null) {
                    appname = String.valueOf(pmUser.getApplicationLabel(info));
                }
            } catch (NameNotFoundException e) {
                // Do nothing
            }
            row.setAppName(appname);
        }

        workAroundBadLayerDrawableOpacity(row);
        View vetoButton = bindVetoButtonClickListener(row, sbn);
        vetoButton.setContentDescription(mContext.getString(
                R.string.accessibility_remove_notification));

        // NB: the large icon is now handled entirely by the template

        // bind the click event to the content area
        NotificationContentView contentContainer = row.getPrivateLayout();
        NotificationContentView contentContainerPublic = row.getPublicLayout();

        row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (ENABLE_REMOTE_INPUT) {
            row.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        }

        mNotificationClicker.register(row, sbn);

        // set up the adaptive layout
        View contentViewLocal = null;
        View bigContentViewLocal = null;
        View headsUpContentViewLocal = null;
        View publicViewLocal = null;
        try {
            contentViewLocal = contentView.apply(
                    sbn.getPackageContext(mContext),
                    contentContainer,
                    mOnClickHandler);
            if (bigContentView != null) {
                bigContentViewLocal = bigContentView.apply(
                        sbn.getPackageContext(mContext),
                        contentContainer,
                        mOnClickHandler);
            }
            if (headsUpContentView != null) {
                headsUpContentViewLocal = headsUpContentView.apply(
                        sbn.getPackageContext(mContext),
                        contentContainer,
                        mOnClickHandler);
            }
            if (publicContentView != null) {
                publicViewLocal = publicContentView.apply(
                        sbn.getPackageContext(mContext),
                        contentContainerPublic, mOnClickHandler);
            }
        }
        catch (RuntimeException e) {
            final String ident = sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId());
            Log.e(TAG, "couldn't inflate view for notification " + ident, e);
            return false;
        }

        if (contentViewLocal != null) {
            contentViewLocal.setIsRootNamespace(true);
            contentContainer.setContractedChild(contentViewLocal);
        }
        if (bigContentViewLocal != null) {
            bigContentViewLocal.setIsRootNamespace(true);
            contentContainer.setExpandedChild(bigContentViewLocal);
        }
        if (headsUpContentViewLocal != null) {
            headsUpContentViewLocal.setIsRootNamespace(true);
            contentContainer.setHeadsUpChild(headsUpContentViewLocal);
        }
        if (publicViewLocal != null) {
            publicViewLocal.setIsRootNamespace(true);
            contentContainerPublic.setContractedChild(publicViewLocal);
        }

        // Extract target SDK version.
        try {
            ApplicationInfo info = pmUser.getApplicationInfo(sbn.getPackageName(), 0);
            entry.targetSdk = info.targetSdkVersion;
        } catch (NameNotFoundException ex) {
            Log.e(TAG, "Failed looking up ApplicationInfo for " + sbn.getPackageName(), ex);
        }
        entry.autoRedacted = entry.notification.getNotification().publicVersion == null;

        if (MULTIUSER_DEBUG) {
            TextView debug = (TextView) row.findViewById(R.id.debug_info);
            if (debug != null) {
                debug.setVisibility(View.VISIBLE);
                debug.setText("CU " + mCurrentUserId +" NU " + entry.notification.getUserId());
            }
        }
        entry.row = row;
        entry.row.setOnActivatedListener(this);
        entry.row.setExpandable(bigContentViewLocal != null);

        applyColorsAndBackgrounds(sbn, entry);

        // Restore previous flags.
        if (hasUserChangedExpansion) {
            // Note: setUserExpanded() conveniently ignores calls with
            //       userExpanded=true if !isExpandable().
            row.setUserExpanded(userExpanded);
        }
        row.onNotificationUpdated(entry);
        return true;
    }

    /**
     * Adds RemoteInput actions from the WearableExtender; to be removed once more apps support this
     * via first-class API.
     *
     * TODO: Remove once enough apps specify remote inputs on their own.
     */
    private void processForRemoteInput(Notification n) {
        if (!ENABLE_REMOTE_INPUT) return;

        if (n.extras != null && n.extras.containsKey("android.wearable.EXTENSIONS") &&
                (n.actions == null || n.actions.length == 0)) {
            Notification.Action viableAction = null;
            Notification.WearableExtender we = new Notification.WearableExtender(n);

            List<Notification.Action> actions = we.getActions();
            final int numActions = actions.size();

            for (int i = 0; i < numActions; i++) {
                Notification.Action action = actions.get(i);
                if (action == null) {
                    continue;
                }
                RemoteInput[] remoteInputs = action.getRemoteInputs();
                if (remoteInputs == null) {
                    continue;
                }
                for (RemoteInput ri : remoteInputs) {
                    if (ri.getAllowFreeFormInput()) {
                        viableAction = action;
                        break;
                    }
                }
                if (viableAction != null) {
                    break;
                }
            }

            if (viableAction != null) {
                Notification.Builder rebuilder = Notification.Builder.recoverBuilder(mContext, n);
                rebuilder.setActions(viableAction);
                rebuilder.build(); // will rewrite n
            }
        }
    }

    public void addPostCollapseAction(Runnable r) {
    }

    public boolean isCollapsing() {
        return false;
    }

    private final class NotificationClicker implements View.OnClickListener {
        public void onClick(final View v) {
            if (!(v instanceof ExpandableNotificationRow)) {
                Log.e(TAG, "NotificationClicker called on a view that is not a notification row.");
                return;
            }

            final ExpandableNotificationRow row = (ExpandableNotificationRow) v;
            final StatusBarNotification sbn = row.getStatusBarNotification();
            if (sbn == null) {
                Log.e(TAG, "NotificationClicker called on an unclickable notification,");
                return;
            }

            // Check if the notification is displaying the gear, if so slide notification back
            if (row.getSettingsRow() != null && row.getSettingsRow().isVisible()) {
                row.animateTranslateNotification(0);
                return;
            }

            Notification notification = sbn.getNotification();
            final PendingIntent intent = notification.contentIntent != null
                    ? notification.contentIntent
                    : notification.fullScreenIntent;
            final String notificationKey = sbn.getKey();

            // Mark notification for one frame.
            row.setJustClicked(true);
            DejankUtils.postAfterTraversal(new Runnable() {
                @Override
                public void run() {
                    row.setJustClicked(false);
                }
            });

            final boolean afterKeyguardGone = intent.isActivity();
        }

        private boolean shouldAutoCancel(StatusBarNotification sbn) {
            int flags = sbn.getNotification().flags;
            if ((flags & Notification.FLAG_AUTO_CANCEL) != Notification.FLAG_AUTO_CANCEL) {
                return false;
            }
            if ((flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                return false;
            }
            return true;
        }

        public void register(ExpandableNotificationRow row, StatusBarNotification sbn) {
            Notification notification = sbn.getNotification();
            if (notification.contentIntent != null || notification.fullScreenIntent != null) {
                row.setOnClickListener(this);
            } else {
                row.setOnClickListener(null);
            }
        }
    }

    public void animateCollapsePanels(int flags, boolean force) {
    }

    public void animateCollapsePanels(int flags, boolean force, boolean delayed) {
    }

    public void overrideActivityPendingAppTransition(boolean keyguardShowing) {
        if (keyguardShowing) {
            try {
                mWindowManagerService.overridePendingAppTransition(null, 0, 0, null);
            } catch (RemoteException e) {
                Log.w(TAG, "Error overriding app transition: " + e);
            }
        }
    }

    protected boolean startWorkChallengeIfNecessary(int userId, IntentSender intendSender,
            String notificationKey) {
        final Intent callBackIntent = new Intent(
                WORK_CHALLENGE_UNLOCKED_NOTIFICATION_ACTION);
        callBackIntent.putExtra(Intent.EXTRA_INTENT, intendSender);
        callBackIntent.putExtra(Intent.EXTRA_INDEX, notificationKey);
        callBackIntent.setPackage(mContext.getPackageName());

        PendingIntent callBackPendingIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                callBackIntent,
                PendingIntent.FLAG_CANCEL_CURRENT |
                        PendingIntent.FLAG_ONE_SHOT |
                        PendingIntent.FLAG_IMMUTABLE);
        newIntent.putExtra(
                Intent.EXTRA_INTENT,
                callBackPendingIntent.getIntentSender());
        try {
            ActivityManagerNative.getDefault().startConfirmDeviceCredentialIntent(newIntent);
        } catch (RemoteException ex) {
            // ignore
        }
        return true;
    }

    protected Bundle getActivityOptions() {
        // Anything launched from the notification shade should always go into the
        // fullscreen stack.
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchStackId(StackId.FULLSCREEN_WORKSPACE_STACK_ID);
        return options.toBundle();
    }

    protected void visibilityChanged(boolean visible) {
        if (mVisible != visible) {
            mVisible = visible;
            if (!visible) {
                dismissPopups();
            }
        }
        updateVisibleToUser();
    }

    protected void updateVisibleToUser() {
        boolean oldVisibleToUser = mVisibleToUser;
        mVisibleToUser = mVisible && mDeviceInteractive;

        if (oldVisibleToUser != mVisibleToUser) {
            handleVisibleToUserChanged(mVisibleToUser);
        }
    }

    /**
     * The LEDs are turned off when the notification panel is shown, even just a little bit.
     * See also PhoneStatusBar.setPanelExpanded for another place where we attempt to do this.
     */
    protected void handleVisibleToUserChanged(boolean visibleToUser) {
        try {
            if (visibleToUser) {
                boolean pinnedHeadsUp = mHeadsUpManager.hasPinnedHeadsUp();
                boolean clearNotificationEffects =
                        !isPanelFullyCollapsed() &&
                        (mState == StatusBarState.SHADE || mState == StatusBarState.SHADE_LOCKED);
                int notificationLoad = mNotificationData.getActiveNotifications().size();
                if (pinnedHeadsUp && isPanelFullyCollapsed())  {
                    notificationLoad = 1;
                } else {
                    MetricsLogger.histogram(mContext, "note_load", notificationLoad);
                }
                mBarService.onPanelRevealed(clearNotificationEffects, notificationLoad);
            } else {
                mBarService.onPanelHidden();
            }
        } catch (RemoteException ex) {
            // Won't fail unless the world has ended.
        }
    }

    /**
     * Clear Buzz/Beep/Blink.
     */
    public void clearNotificationEffects() {
        try {
            mBarService.clearNotificationEffects();
        } catch (RemoteException e) {
            // Won't fail unless the world has ended.
        }
    }

    public abstract boolean isPanelFullyCollapsed();

    /**
     * Cancel this notification and tell the StatusBarManagerService / NotificationManagerService
     * about the failure.
     *
     * WARNING: this will call back into us.  Don't hold any locks.
     */
    void handleNotificationError(StatusBarNotification n, String message) {
        removeNotification(n.getKey(), null);
        try {
            mBarService.onNotificationError(n.getPackageName(), n.getTag(), n.getId(), n.getUid(),
                    n.getInitialPid(), message, n.getUserId());
        } catch (RemoteException ex) {
            // The end is nigh.
        }
    }

    protected StatusBarNotification removeNotificationViews(String key, RankingMap ranking) {
        NotificationData.Entry entry = mNotificationData.remove(key, ranking);
        if (entry == null) {
            Log.w(TAG, "removeNotification for unknown key: " + key);
            return null;
        }
        updateNotifications();
        return entry.notification;
    }

    protected NotificationData.Entry createNotificationViews(StatusBarNotification sbn) {
        if (DEBUG) {
            Log.d(TAG, "createNotificationViews(notification=" + sbn);
        }
        final StatusBarIconView iconView = createIcon(sbn);
        if (iconView == null) {
            return null;
        }

        // Construct the expanded view.
        NotificationData.Entry entry = new NotificationData.Entry(sbn, iconView);
        if (!inflateViews(entry, mStackScroller)) {
            handleNotificationError(sbn, "Couldn't expand RemoteViews for: " + sbn);
            return null;
        }
        return entry;
    }

    public StatusBarIconView createIcon(StatusBarNotification sbn) {
        // Construct the icon.
        Notification n = sbn.getNotification();
        final StatusBarIconView iconView = new StatusBarIconView(mContext,
                sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), n);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        final Icon smallIcon = n.getSmallIcon();
        if (smallIcon == null) {
            handleNotificationError(sbn,
                    "No small icon in notification from " + sbn.getPackageName());
            return null;
        }
        final StatusBarIcon ic = new StatusBarIcon(
                sbn.getUser(),
                sbn.getPackageName(),
                smallIcon,
                n.iconLevel,
                n.number,
                StatusBarIconView.contentDescForNotification(mContext, n));
        if (!iconView.set(ic)) {
            handleNotificationError(sbn, "Couldn't create icon: " + ic);
            return null;
        }
        return iconView;
    }

    protected void addNotificationViews(Entry entry, RankingMap ranking) {
        if (entry == null) {
            return;
        }
        // Add the expanded view and icon.
        mNotificationData.add(entry, ranking);
        updateNotifications();
    }

    /**
     * Updates expanded, dimmed and locked states of notification rows.
     */
    protected void updateRowStates() {
        ArrayList<Entry> activeNotifications = mNotificationData.getActiveNotifications();
        final int N = activeNotifications.size();

        int visibleNotifications = 0;
        int maxNotifications = 0;
        for (int i = 0; i < N; i++) {
            NotificationData.Entry entry = activeNotifications.get(i);
            boolean childNotification = mGroupManager.isChildInGroupWithSummary(entry.notification);
            entry.row.setSystemExpanded(visibleNotifications == 0 && !childNotification);
            boolean suppressedSummary = mGroupManager.isSummaryOfSuppressedGroup(
                    entry.notification) && !entry.row.isRemoved();
            boolean childWithVisibleSummary = childNotification
                    && mGroupManager.getGroupSummary(entry.notification).getVisibility()
                    == View.VISIBLE;
            if (suppressedSummary && !childWithVisibleSummary
                            && (visibleNotifications >= maxNotifications)) {
                entry.row.setVisibility(View.GONE);
            } else {
                boolean wasGone = entry.row.getVisibility() == View.GONE;
                entry.row.setVisibility(View.VISIBLE);
                if (!childNotification && !entry.row.isRemoved()) {
                    if (wasGone) {
                        // notify the scroller of a child addition
                        mStackScroller.generateAddAnimation(entry.row /* fromMoreCard */);
                    }
                    visibleNotifications++;
                }
            }
        }

        mStackScroller.updateOverflowContainerVisibility(mStackScroller.getChildCount() > 0);

        mStackScroller.changeViewPosition(mDismissView, mStackScroller.getChildCount() - 1);
        mStackScroller.changeViewPosition(mEmptyShadeView, mStackScroller.getChildCount() - 2);
        mStackScroller.changeViewPosition(mKeyguardIconOverflowContainer,
                mStackScroller.getChildCount() - 3);
    }

    protected abstract void setAreThereNotifications();
    protected abstract void updateNotifications();
    
    public abstract void addNotification(StatusBarNotification notification,
            RankingMap ranking, Entry oldEntry);
    protected abstract void updateNotificationRanking(RankingMap ranking);
    public abstract void removeNotification(String key, RankingMap ranking);

    public void updateNotification(StatusBarNotification notification, RankingMap ranking) {
        if (DEBUG) Log.d(TAG, "updateNotification(" + notification + ")");

        final String key = notification.getKey();
        Entry entry = mNotificationData.get(key);
        if (entry == null) {
            return;
        } else {
            mHeadsUpEntriesToRemoveOnSwitch.remove(entry);
            mRemoteInputEntriesToRemoveOnCollapse.remove(entry);
        }

        Notification n = notification.getNotification();
        mNotificationData.updateRanking(ranking);

        boolean applyInPlace = entry.cacheContentViews(mContext, notification.getNotification());
        boolean shouldPeek = shouldPeek(entry, notification);
        boolean alertAgain = alertAgain(entry, n);
        if (DEBUG) {
            Log.d(TAG, "applyInPlace=" + applyInPlace
                    + " shouldPeek=" + shouldPeek
                    + " alertAgain=" + alertAgain);
        }

        final StatusBarNotification oldNotification = entry.notification;
        entry.notification = notification;
        mGroupManager.onEntryUpdated(entry, oldNotification);

        boolean updateSuccessful = false;
        if (applyInPlace) {
            if (DEBUG) Log.d(TAG, "reusing notification for key: " + key);
            try {
                if (entry.icon != null) {
                    // Update the icon
                    final StatusBarIcon ic = new StatusBarIcon(
                            notification.getUser(),
                            notification.getPackageName(),
                            n.getSmallIcon(),
                            n.iconLevel,
                            n.number,
                            StatusBarIconView.contentDescForNotification(mContext, n));
                    entry.icon.setNotification(n);
                    if (!entry.icon.set(ic)) {
                        handleNotificationError(notification, "Couldn't update icon: " + ic);
                        return;
                    }
                }
                updateNotificationViews(entry, notification);
                updateSuccessful = true;
            }
            catch (RuntimeException e) {
                // It failed to apply cleanly.
                Log.w(TAG, "Couldn't reapply views for package " +
                        notification.getPackageName(), e);
            }
        }
        if (!updateSuccessful) {
            if (DEBUG) Log.d(TAG, "not reusing notification for key: " + key);
            final StatusBarIcon ic = new StatusBarIcon(
                    notification.getUser(),
                    notification.getPackageName(),
                    n.getSmallIcon(),
                    n.iconLevel,
                    n.number,
                    StatusBarIconView.contentDescForNotification(mContext, n));
            entry.icon.setNotification(n);
            entry.icon.set(ic);
            inflateViews(entry, mStackScroller);
        }
        updateHeadsUp(key, entry, shouldPeek, alertAgain);
        updateNotifications();

        // Update the veto button accordingly (and as a result, whether this row is
        // swipe-dismissable)
        bindVetoButtonClickListener(entry.row, notification);

        if (!notification.isClearable()) {
            // The user may have performed a dismiss action on the notification, since it's
            // not clearable we should snap it back.
            mStackScroller.snapViewIfNeeded(entry.row);
        }

        if (DEBUG) {
            // Is this for you?
            boolean isForCurrentUser = isNotificationForCurrentProfiles(notification);
            Log.d(TAG, "notification is " + (isForCurrentUser ? "" : "not ") + "for you");
        }

        setAreThereNotifications();
    }

    protected abstract void updateHeadsUp(String key, Entry entry, boolean shouldPeek,
            boolean alertAgain);

    private void updateNotificationViews(Entry entry, StatusBarNotification sbn) {
        final RemoteViews contentView = entry.cachedContentView;
        final RemoteViews bigContentView = entry.cachedBigContentView;
        final RemoteViews headsUpContentView = entry.cachedHeadsUpContentView;
        final RemoteViews publicContentView = entry.cachedPublicContentView;

        // Reapply the RemoteViews
        contentView.reapply(mContext, entry.getContentView(), mOnClickHandler);
        if (bigContentView != null && entry.getExpandedContentView() != null) {
            bigContentView.reapply(sbn.getPackageContext(mContext),
                    entry.getExpandedContentView(),
                    mOnClickHandler);
        }
        View headsUpChild = entry.getHeadsUpContentView();
        if (headsUpContentView != null && headsUpChild != null) {
            headsUpContentView.reapply(sbn.getPackageContext(mContext),
                    headsUpChild, mOnClickHandler);
        }
        if (publicContentView != null && entry.getPublicContentView() != null) {
            publicContentView.reapply(sbn.getPackageContext(mContext),
                    entry.getPublicContentView(), mOnClickHandler);
        }
        // update the contentIntent
        mNotificationClicker.register(entry.row, sbn);

        entry.row.onNotificationUpdated(entry);
        entry.row.resetHeight();
    }

    protected void updatePublicContentView(Entry entry,
            StatusBarNotification sbn) {
        final RemoteViews publicContentView = entry.cachedPublicContentView;
        View inflatedView = entry.getPublicContentView();
        if (entry.autoRedacted && publicContentView != null && inflatedView != null) {
            final boolean disabledByPolicy =
                    !adminAllowsUnredactedNotifications(entry.notification.getUserId());
            String notificationHiddenText = mContext.getString(disabledByPolicy
                    ? com.android.internal.R.string.notification_hidden_by_policy_text
                    : com.android.internal.R.string.notification_hidden_text);
            TextView titleView = (TextView) inflatedView.findViewById(android.R.id.title);
            if (titleView != null
                    && !titleView.getText().toString().equals(notificationHiddenText)) {
                publicContentView.setTextViewText(android.R.id.title, notificationHiddenText);
                publicContentView.reapply(sbn.getPackageContext(mContext),
                        inflatedView, mOnClickHandler);
                entry.row.onNotificationUpdated(entry);
            }
        }
    }

    protected void notifyHeadsUpScreenOff() {
        maybeEscalateHeadsUp();
    }

    private boolean alertAgain(Entry oldEntry, Notification newNotification) {
        return oldEntry == null || !oldEntry.hasInterrupted()
                || (newNotification.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0;
    }

    protected boolean shouldPeek(Entry entry) {
        return shouldPeek(entry, entry.notification);
    }

    protected boolean shouldPeek(Entry entry, StatusBarNotification sbn) {
        if (!mUseHeadsUp || isDeviceInVrMode()) {
            return false;
        }

        if (mNotificationData.shouldFilterOut(sbn)) {
            if (DEBUG) Log.d(TAG, "No peeking: filtered notification: " + sbn.getKey());
            return false;
        }

        boolean inUse = mPowerManager.isScreenOn();
        try {
            inUse = inUse && !mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.d(TAG, "failed to query dream manager", e);
        }

        if (!inUse) {
            if (DEBUG) {
                Log.d(TAG, "No peeking: not in use: " + sbn.getKey());
            }
            return false;
        }

        if (mNotificationData.shouldSuppressScreenOn(sbn.getKey())) {
            if (DEBUG) Log.d(TAG, "No peeking: suppressed by DND: " + sbn.getKey());
            return false;
        }

        if (entry.hasJustLaunchedFullScreenIntent()) {
            if (DEBUG) Log.d(TAG, "No peeking: recent fullscreen: " + sbn.getKey());
            return false;
        }

        if (isSnoozedPackage(sbn)) {
            if (DEBUG) Log.d(TAG, "No peeking: snoozed package: " + sbn.getKey());
            return false;
        }

        if (mNotificationData.getImportance(sbn.getKey()) < IMPORTANCE_HIGH) {
            if (DEBUG) Log.d(TAG, "No peeking: unimportant notification: " + sbn.getKey());
            return false;
        }

        if (sbn.getNotification().fullScreenIntent != null) {
            if (mAccessibilityManager.isTouchExplorationEnabled()) {
                if (DEBUG) Log.d(TAG, "No peeking: accessible fullscreen: " + sbn.getKey());
                return false;
            } else {
                return true;
            }
        }

        return true;
    }

    protected abstract boolean isSnoozedPackage(StatusBarNotification sbn);

    public void setInteracting(int barWindow, boolean interacting) {
        // hook for subclasses
    }

    public void destroy() {
        mContext.unregisterReceiver(mBroadcastReceiver);
        try {
            mNotificationListener.unregisterAsSystemService();
        } catch (RemoteException e) {
            // Ignore.
        }
    }

    /**
     * @return a PackageManger for userId or if userId is < 0 (USER_ALL etc) then
     *         return PackageManager for mContext
     */
    public static PackageManager getPackageManagerForUser(Context context, int userId) {
        Context contextForUser = context;
        // UserHandle defines special userId as negative values, e.g. USER_ALL
        if (userId >= 0) {
            try {
                // Create a context for the correct user so if a package isn't installed
                // for user 0 we can still load information about the package.
                contextForUser =
                        context.createPackageContextAsUser(context.getPackageName(),
                        Context.CONTEXT_RESTRICTED,
                        new UserHandle(userId));
            } catch (NameNotFoundException e) {
                // Shouldn't fail to find the package name for system ui.
            }
        }
        return contextForUser.getPackageManager();
    }

    @Override
    public void logNotificationExpansion(String key, boolean userAction, boolean expanded) {
        try {
            mBarService.onNotificationExpansionChanged(key, userAction, expanded);
        } catch (RemoteException e) {
            // Ignore.
        }
    }

    @Override
    public void showAssistDisclosure() {
        if (mAssistManager != null) {
            mAssistManager.showDisclosure();
        }
    }

    @Override
    public void startAssist(Bundle args) {
        if (mAssistManager != null) {
            mAssistManager.startAssist(args);
        }
    }
}
