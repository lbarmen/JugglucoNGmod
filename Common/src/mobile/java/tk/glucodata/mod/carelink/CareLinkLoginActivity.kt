package tk.glucodata.mod.carelink

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.ui.JugglucoTheme

/**
 * JugglucoNGmod, CareLink (stage 4b): the CareLink sign-in window - the Medtronic sign-in page in a WebView. User
 * name, password and a second-factor code are typed on the Medtronic page; MOD never sees them. The return address is
 * caught ([CareLinkConfig.isRedirect]), the page goes no further, and the code is exchanged for session keys by
 * [CareLinkAuth.finishLogin] in a scope that outlives the window. Page addresses (they carry the code and the mark) are
 * never logged. Rotation and keyboard do not recreate the window (configChanges in the manifest); if the system does
 * recreate it, the page is restored from saveState and the waiting sign-in (its mark) from pending.json.
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml (exported=false). Result: RESULT_OK - signed in;
 * RESULT_CANCELED - closed or failed ([EXTRA_FAIL] = [LoginFail] name when it failed).
 */
class CareLinkLoginActivity : ComponentActivity() {

    private var webView: WebView? = null

    /** Window-bound work (step 1); cancelled with the window. The code exchange runs in [CareLinkAuth.scope]. */
    private val uiScope = MainScope()
    private var redirect: String? = null

    /** The return was caught - a second call (onPageStarted after shouldOverrideUrlLoading) is ignored. */
    private var redirected = false

    private var loading by mutableStateOf(true)
    private var finishing by mutableStateOf(false)
    private var showPage by mutableStateOf(false)
    private var failText by mutableStateOf<String?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (_: Throwable) {
        }
        val themeMode = CareLinkTheme.mode(this)
        val web = try {
            newWebView()
        } catch (t: Throwable) {
            // no WebView on the phone (disabled, updating): say so instead of crashing upstream's app
            CareLinkLog.w("sign-in window: WebView not available (${t.javaClass.simpleName})")
            null
        }
        webView = web

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = webView
                when {
                    finishing -> Unit // the code exchange is running - wait for its result
                    showPage && w != null && w.canGoBack() -> w.goBack()
                    else -> close(null)
                }
            }
        })

        setContent {
            JugglucoTheme(themeMode = themeMode) {
                val colors = MaterialTheme.colorScheme
                Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.mod_carelink_login_title),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onBackground,
                        )
                        TextButton(onClick = { close(null) }, enabled = !finishing) {
                            Text(stringResource(R.string.mod_carelink_login_close))
                        }
                    }
                    if ((loading || finishing) && failText == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    val fail = failText
                    when {
                        fail != null -> Message(fail)
                        finishing -> Message(stringResource(R.string.mod_carelink_login_busy))
                        showPage && webView != null -> AndroidView(factory = { webView!! }, modifier = Modifier.fillMaxSize())
                        else -> Unit
                    }
                }
            }
        }

        if (web == null) {
            failText = getString(R.string.mod_carelink_login_fail_webview)
            loading = false
            return
        }
        // recreated (even after the process was unloaded): the page from saveState, the return address from the
        // bundle; the mark of the waiting sign-in stays in pending.json for finishLogin
        val savedRedirect = savedInstanceState?.getString(STATE_REDIRECT)
        if (savedInstanceState != null && savedRedirect != null && web.restoreState(savedInstanceState) != null) {
            redirect = savedRedirect
            showPage = true
            loading = false
        } else {
            start(web)
        }
    }

    @Composable
    private fun Message(text: String) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }

    /** Step 1 off the main thread, then the page. */
    private fun start(web: WebView) {
        loading = true
        uiScope.launch {
            val r = try {
                CareLinkAuth.beginLogin(applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                CareLinkLog.w("sign-in not started (${t.javaClass.simpleName})")
                LoginStart.Failed(LoginFail.SERVER, 0)
            }
            when (r) {
                is LoginStart.Ready -> {
                    redirect = r.redirectUri
                    showPage = true
                    web.loadUrl(r.uri.toString())
                }
                is LoginStart.Failed -> {
                    loading = false
                    failText = failMessage(r.reason)
                    setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_FAIL, r.reason.name))
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun newWebView(): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                val r = redirect
                if (r != null && CareLinkConfig.isRedirect(u, r)) {
                    onRedirect(u)
                    return true
                }
                // intent://, market:// and other schemes - never opened (no other apps, no store)
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // fallback: some transitions (server redirects) never reach shouldOverrideUrlLoading
                val r = redirect
                if (url != null && r != null && CareLinkConfig.isRedirect(url, r)) {
                    view.stopLoading()
                    onRedirect(url)
                    return
                }
                loading = true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
            }
        }
    }

    /** The return from the sign-in page: once - the code exchange in CareLinkAuth's scope (the window leaving does not cut it). */
    private fun onRedirect(url: String) {
        if (redirected) return
        redirected = true
        finishing = true
        loading = false
        showPage = false
        CareLinkAuth.scope.launch {
            val fail = try {
                CareLinkAuth.finishLogin(applicationContext, url)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // without the address: it holds the code and the mark
                CareLinkLog.w("sign-in failed (${t.javaClass.simpleName})")
                LoginFail.SERVER
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (fail == null) {
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    finishing = false
                    failText = failMessage(fail)
                    setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_FAIL, fail.name))
                }
            }
        }
    }

    private fun failMessage(fail: LoginFail): String = getString(
        when (fail) {
            LoginFail.NO_NETWORK -> R.string.mod_carelink_login_fail_network
            LoginFail.SERVER -> R.string.mod_carelink_login_fail_server
            LoginFail.UNSUPPORTED -> R.string.mod_carelink_login_fail_unsupported
            LoginFail.STORAGE -> R.string.mod_carelink_login_fail_storage
            else -> R.string.mod_carelink_login_fail_generic
        },
    )

    private fun close(fail: LoginFail?) {
        if (finishing) return
        if (fail != null) setResult(Activity.RESULT_CANCELED, Intent().putExtra(EXTRA_FAIL, fail.name))
        finish()
    }

    /** Gone to mail or SMS for a code - the sign-in page cookies to disk now (the process may be unloaded). */
    override fun onStop() {
        super.onStop()
        try {
            CookieManager.getInstance().flush()
        } catch (t: Throwable) {
            CareLinkLog.w("sign-in cookies not flushed (${t.javaClass.simpleName})")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            if (showPage) {
                webView?.saveState(outState)
                redirect?.let { outState.putString(STATE_REDIRECT, it) }
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        webView?.let {
            try {
                it.stopLoading()
                it.destroy()
            } catch (_: Throwable) {
            }
        }
        webView = null
        uiScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FAIL = "mod_carelink_login_fail"
        private const val STATE_REDIRECT = "mod_carelink_redirect"

        fun intent(context: Context): Intent = Intent(context, CareLinkLoginActivity::class.java)
    }
}
