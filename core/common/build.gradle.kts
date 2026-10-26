plugins { id("heycam.android.library"); id("heycam.hilt") }
android { namespace = "heycam.core.common" }
dependencies { api(project(":core:model")) }
