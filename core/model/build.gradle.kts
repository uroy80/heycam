// Android library on purpose: a kotlin("jvm") module needs KGP types in build-logic to align jvmTarget (see PLAN G3).
// Rule: no android.* imports in this module (CI grep).
plugins { id("heycam.android.library") }
android { namespace = "heycam.core.model" }
dependencies { api(libs.kotlinx.coroutines.core) }
