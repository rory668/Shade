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

package com.studio.shade.statusbar.phone;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewStub;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import com.studio.shade.AutoReinflateContainer;
import com.studio.shade.R;
import com.studio.shade.qs.QSContainer;
import com.studio.shade.qs.customize.QSCustomizer;

/**
 * The container with notification stack scroller and quick settings inside.
 */
public class NotificationsQuickSettingsContainer extends FrameLayout
        implements ViewStub.OnInflateListener, AutoReinflateContainer.InflateListener {


    private AutoReinflateContainer mQsContainer;
    private View mUserSwitcher;
    private View mStackScroller;
    private boolean mInflated;
    private boolean mQsExpanded;
    private boolean mCustomizerAnimating;

    private int mBottomPadding;
    private int mStackScrollerMargin;

    public NotificationsQuickSettingsContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mQsContainer = (AutoReinflateContainer) findViewById(R.id.qs_auto_reinflate_container);
        mQsContainer.addInflateListener(this);
        mStackScroller = findViewById(R.id.notification_stack_scroller);
        mStackScrollerMargin = ((LayoutParams) mStackScroller.getLayoutParams()).bottomMargin;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        reloadWidth(mQsContainer);
        reloadWidth(mStackScroller);
    }

    private void reloadWidth(View view) {
        LayoutParams params = (LayoutParams) view.getLayoutParams();
        params.width = getContext().getResources().getDimensionPixelSize(
                R.dimen.notification_panel_width);
        view.setLayoutParams(params);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mBottomPadding = insets.getStableInsetBottom();
        setPadding(0, 0, 0, mBottomPadding);
        return insets;
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        final boolean qsBottom = mQsExpanded && !mCustomizerAnimating;
        View stackQsTop = qsBottom ? mStackScroller : mQsContainer;
        View stackQsBottom = !qsBottom ? mStackScroller : mQsContainer;
        // Invert the order of the scroll view and user switcher such that the notifications receive
        // touches first but the panel gets drawn above.
        if (child == mQsContainer) {
            return super.drawChild(canvas, stackQsBottom, drawingTime);
        } else if (child == mStackScroller) {
            return super.drawChild(canvas, statusBarVisible ? stackQsBottom
                    : stackQsTop, drawingTime);
        } else {
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    @Override
    public void onInflate(ViewStub stub, View inflated) {
    }

    @Override
    public void onInflated(View v) {
        QSCustomizer customizer = ((QSContainer) v).getCustomizer();
        customizer.setContainer(this);
    }

    public void setQsExpanded(boolean expanded) {
        if (mQsExpanded != expanded) {
            mQsExpanded = expanded;
            invalidate();
        }
    }

    public void setCustomizerAnimating(boolean isAnimating) {
        if (mCustomizerAnimating != isAnimating) {
            mCustomizerAnimating = isAnimating;
            invalidate();
        }
    }

    public void setCustomizerShowing(boolean isShowing) {
        if (isShowing) {
            // Clear out bottom paddings/margins so the qs customization can be full height.
            setPadding(0, 0, 0, 0);
            setBottomMargin(mStackScroller, 0);
        } else {
            setPadding(0, 0, 0, mBottomPadding);
            setBottomMargin(mStackScroller, mStackScrollerMargin);
        }

    }

    private void setBottomMargin(View v, int bottomMargin) {
        LayoutParams params = (LayoutParams) v.getLayoutParams();
        params.bottomMargin = bottomMargin;
        v.setLayoutParams(params);
    }
}
