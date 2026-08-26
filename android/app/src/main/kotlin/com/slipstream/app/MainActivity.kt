package com.slipstream.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.slipstream.app.permissions.PermissionGate
import com.slipstream.app.ui.SlipstreamNavHost

/**
 * Starts [PeerForegroundService] on launch and runs [PermissionGate] (Task 12) to ask, at most
 * once per install and each with a plain-language rationale first, for `POST_NOTIFICATIONS`,
 * `MANAGE_EXTERNAL_STORAGE`, and the battery-optimisation exemption (design.md §2, §14). The app
 * works fully with any (or all) of them denied - see [PermissionGate]'s class doc.
 *
 * Extends [ComponentActivity] (rather than plain `Activity`) so it can host Compose content via
 * [setContent] - the navigation shell built in [SlipstreamNavHost]. [ComponentActivity] is
 * itself an `Activity`, so [PermissionGate]'s use of `requestPermissions`/`startActivity` is
 * unaffected.
 */
class MainActivity : ComponentActivity() {

    /** Internal so a test can drive it directly without duplicating [PermissionGate]'s own
     * dialog-interaction plumbing. */
    internal val permissionGate: PermissionGate by lazy { PermissionGate(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContextCompat.startForegroundService(this, Intent(this, PeerForegroundService::class.java))
        permissionGate.requestAll()

        setContent {
            val app = application as SlipstreamApplication
            var sharedUris by remember { mutableStateOf(sharedUrisFromIntent(intent)) }
            currentSharedUrisSetter = { sharedUris = it }
            SlipstreamNavHost(
                peerController = app.peerController,
                settingsStore = app.settingsStore,
                sharedUris = sharedUris,
            )
        }
    }

    /** Set by [onCreate]'s composition so [onNewIntent] can push freshly-shared URIs into the
     * already-running composition, rather than only ever reading [getIntent] once at launch. */
    private var currentSharedUrisSetter: ((List<Uri>) -> Unit)? = null

    /**
     * Minor finding: a share-sheet `ACTION_SEND`/`ACTION_SEND_MULTIPLE` intent arriving while
     * Slipstream is already running (singleTop/singleTask launch mode) previously landed here
     * silently, since [onCreate] only ever reads the intent once at launch. Re-extracts the
     * shared URI(s) the same way [onCreate] does and routes them the same way, then calls
     * [setIntent] so a later [getIntent] (e.g. after a config change) sees the new intent instead
     * of the stale launch one.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentSharedUrisSetter?.invoke(sharedUrisFromIntent(intent))
    }

    /**
     * Task 10: when Slipstream is launched from the share sheet (`ACTION_SEND` /
     * `ACTION_SEND_MULTIPLE`, per the manifest's new intent filters), extracts the shared
     * item(s) so [SlipstreamNavHost] can route straight to the send screen with them pre-queued.
     * Returns an empty list for an ordinary launch (`ACTION_MAIN`) - the overwhelmingly common
     * case - so the nav host's default behaviour is unaffected.
     */
    internal fun sharedUrisFromIntent(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND ->
            intent.getParcelableExtraCompat(Intent.EXTRA_STREAM, Uri::class.java)?.let { listOf(it) } ?: emptyList()

        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtraCompat(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()

        else -> emptyList()
    }
}

/** [Intent.getParcelableExtra] with a [Class] argument is API 33+; the single-argument overload
 * it replaces is deprecated but still the only option below that. Both branches return the same
 * thing - this just picks whichever one the running OS actually has. */
private fun <T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }

private fun <T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(
    name: String,
    clazz: Class<T>,
): java.util.ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(name, clazz)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(name)
    }
