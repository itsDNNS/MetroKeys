# R8 rules for the release build of MetroKeys (applied on top of proguard-android-optimize.txt).
#
# None are needed at the moment:
# - The manifest components (input method service, settings activity, clipboard image provider) are kept by the
#   rules AAPT generates from the manifest.
# - The engine reads its dictionaries with absolute Java resource paths (/app/metrokeys/engine/dict/...), which
#   do not depend on class or package names, and R8 keeps Java resources.
# - The only reflective call is to a framework method (AccessibilityManager.isHighTextContrastEnabled), which
#   R8 does not rename.
#
# Add a rule here only together with a comment that names the code that needs it.
