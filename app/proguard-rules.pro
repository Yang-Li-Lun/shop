# ML Kit publishes its own consumer rules. Keep the AccessibilityService entry point.
-keep class com.example.csc.automation.ScreenAutomationService { *; }

# Firebase discovers ML Kit registrars by manifest name and public no-arg reflection.
# Keeping only the class (the bundled consumer rule) does not retain its constructor.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}