plugins { id("heycam.android.library"); id("heycam.hilt") }
android { namespace = "heycam.core.security" }
dependencies { api(project(":core:model")); api(project(":core:common")); api(libs.androidx.activity.compose) }
