package com.studio.shade.tuner;

import android.content.Context;
import android.content.res.TypedArray;
import android.provider.Settings;
import android.support.v14.preference.SwitchPreference;
import android.util.AttributeSet;

import com.android.internal.logging.MetricsLogger;
import com.studio.shade.R;
import com.studio.shade.tuner.TunerService.Tunable;

public class TunerSwitch extends SwitchPreference implements Tunable {

    private final boolean mDefault;
    private final int mAction;

    public TunerSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TunerSwitch);
        mDefault = a.getBoolean(R.styleable.TunerSwitch_defValue, false);
        mAction = a.getInt(R.styleable.TunerSwitch_metricsAction, -1);
    }

    @Override
    public void onAttached() {
        super.onAttached();
        TunerService.get(getContext()).addTunable(this, getKey().split(","));
    }

    @Override
    public void onDetached() {
        TunerService.get(getContext()).removeTunable(this);
        super.onDetached();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        setChecked(newValue != null ? Integer.parseInt(newValue) != 0 : mDefault);
    }

    @Override
    protected void onClick() {
        super.onClick();
        if (mAction != -1) {
            MetricsLogger.action(getContext(), mAction, isChecked());
        }
    }

    @Override
    protected boolean persistBoolean(boolean value) {
        for (String key : getKey().split(",")) {
            Settings.Secure.putString(getContext().getContentResolver(), key, value ? "1" : "0");
        }
        return true;
    }

}
