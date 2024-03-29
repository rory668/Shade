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
 * limitations under the License
 */

package com.studio.shade;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Process;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Application class for SystemUI.
 */
public class SystemUIApplication extends Application {

    private static final String TAG = "SystemUIService";
    private static final boolean DEBUG = false;

    /**
     * The classes of the stuff to start.
     */
    private final Class<?>[] SERVICES = new Class[] {
            com.studio.shade.tuner.TunerService.class,
            com.studio.shade.statusbar.SystemBars.class,
            com.studio.shade.media.RingtonePlayer.class
    };

    /**
     * The classes of the stuff to start for each user.  This is a subset of the services listed
     * above.
     */
    private final Class<?>[] SERVICES_PER_USER = new Class[] {
    };

    /**
     * Hold a reference on the stuff we start.
     */
    private final SystemUI[] mServices = new SystemUI[SERVICES.length];
    private boolean mServicesStarted;
    private boolean mBootCompleted;
    private final Map<Class<?>, Object> mComponents = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        // Set the application theme that is inherited by all services. Note that setting the
        // application theme in the manifest does only work for activities. Keep this in sync with
        // the theme set there.
        setTheme(R.style.systemui_theme);

        SystemUIFactory.createFromConfig(this);

        if (Process.myUserHandle().equals(UserHandle.SYSTEM)) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mBootCompleted) return;

                    if (DEBUG) Log.v(TAG, "BOOT_COMPLETED received");
                    unregisterReceiver(this);
                    mBootCompleted = true;
                    if (mServicesStarted) {
                        final int N = mServices.length;
                        for (int i = 0; i < N; i++) {
                            mServices[i].onBootCompleted();
                        }
                    }
                }
            }, filter);
        } else {
            // For a secondary user, boot-completed will never be called because it has already
            // been broadcasted on startup for the primary SystemUI process.  Instead, for
            // components which require the SystemUI component to be initialized per-user, we
            // start those components now for the current non-system user.
            startServicesIfNeeded(SERVICES_PER_USER);
        }
    }

    /**
     * Makes sure that all the SystemUI services are running. If they are already running, this is a
     * no-op. This is needed to conditinally start all the services, as we only need to have it in
     * the main process.
     *
     * <p>This method must only be called from the main thread.</p>
     */
    public void startServicesIfNeeded() {
        startServicesIfNeeded(SERVICES);
    }

    /**
     * Ensures that all the Secondary user SystemUI services are running. If they are already
     * running, this is a no-op. This is needed to conditinally start all the services, as we only
     * need to have it in the main process.
     *
     * <p>This method must only be called from the main thread.</p>
     */
    void startSecondaryUserServicesIfNeeded() {
        startServicesIfNeeded(SERVICES_PER_USER);
    }

    private void startServicesIfNeeded(Class<?>[] services) {
        if (mServicesStarted) {
            return;
        }

        if (!mBootCompleted) {
            // check to see if maybe it was already completed long before we began
            // see ActivityManagerService.finishBooting()
            if ("1".equals(SystemProperties.get("sys.boot_completed"))) {
                mBootCompleted = true;
                if (DEBUG) Log.v(TAG, "BOOT_COMPLETED was already sent");
            }
        }

        Log.v(TAG, "Starting SystemUI services for user " +
                Process.myUserHandle().getIdentifier() + ".");
        final int N = services.length;
        for (int i=0; i<N; i++) {
            Class<?> cl = services[i];
            if (DEBUG) Log.d(TAG, "loading: " + cl);
            try {
                Object newService = SystemUIFactory.getInstance().createInstance(cl);
                mServices[i] = (SystemUI) ((newService == null) ? cl.newInstance() : newService);
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            } catch (InstantiationException ex) {
                throw new RuntimeException(ex);
            }

            mServices[i].mContext = this;
            mServices[i].mComponents = mComponents;
            if (DEBUG) Log.d(TAG, "running: " + mServices[i]);
            mServices[i].start();

            if (mBootCompleted) {
                mServices[i].onBootCompleted();
            }
        }
        mServicesStarted = true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mServicesStarted) {
            int len = mServices.length;
            for (int i = 0; i < len; i++) {
                if (mServices[i] != null) {
                    mServices[i].onConfigurationChanged(newConfig);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getComponent(Class<T> interfaceType) {
        return (T) mComponents.get(interfaceType);
    }

    public SystemUI[] getServices() {
        return mServices;
    }
}
