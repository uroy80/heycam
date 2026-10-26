plugins { id("heycam.android.library"); id("heycam.hilt") }
android { namespace = "heycam.core.imaging" }
dependencies { api(project(":core:model")); api(project(":core:common")) }
