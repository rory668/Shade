-keep class com.studio.shade.statusbar.policy.KeyButtonView {
  public float getDrawingAlpha();
  public void setDrawingAlpha(float);
}

-keep class com.studio.shade.statusbar.policy.KeyButtonRipple {
  public float getGlowAlpha();
  public float getGlowScale();
  public void setGlowAlpha(float);
  public void setGlowScale(float);
}

-keep class com.studio.shade.statusbar.phone.PhoneStatusBar
-keep class com.studio.shade.SystemUIFactory

-keepclassmembers class ** {
    public void onBusEvent(**);
    public void onInterprocessBusEvent(**);
}
-keepclassmembers class ** extends **.EventBus$InterprocessEvent {
    public <init>(android.os.Bundle);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keep class ** extends android.support.v14.preference.PreferenceFragment
-keep class com.studio.shade.tuner.*
