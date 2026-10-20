package vn.anta.booking

import android.app.Application
import vn.anta.booking.data.ApiClient
import vn.anta.booking.data.DevelopmentBackend

/** A release build has no development helpers. */
@Suppress("UNUSED_PARAMETER")
fun installDevelopmentTools(app: Application) = Unit

/** A release build has no development helpers. */
@Suppress("UNUSED_PARAMETER")
fun developmentBackend(api: ApiClient): DevelopmentBackend? = null
