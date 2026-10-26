# The test APK's R8 rules (testProguardFile; only with -Pheycam.testBuildType=release, PLAN D23).
# AGP already keeps every class in the test APK and points its references into the app at the app's mapping (see
# build/outputs/mapping/releaseAndroidTest/configuration.txt), so nothing here keeps anything. What the app must keep
# for the tests is in r8-test-entrypoints.pro.

# The tests run as written: no inlining, outlining or lambda merging in the test APK, which also keeps
# scripts/check-test-apk.py's view of what the tests reach exact.
-dontoptimize

# JUnit, AndroidX Test, Truth and Guava name optional classes that Android does not have.
-dontwarn org.junit.**
-dontwarn androidx.test.**
-dontwarn javax.lang.model.element.Modifier
