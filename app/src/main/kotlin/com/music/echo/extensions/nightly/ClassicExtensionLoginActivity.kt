package iad1tya.echo.music.extensions.nightly

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.helpers.WebViewClient as EchoWebViewClient
import dev.brahmkshatriya.echo.common.helpers.WebViewRequest as EchoWebViewRequest
import dev.brahmkshatriya.echo.common.models.Metadata
import dev.brahmkshatriya.echo.common.models.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Hosts both extension account login and extension-requested web authentication. */
class ClassicExtensionLoginActivity : ComponentActivity() {
    private var pendingRequestId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRequestId = intent.getIntExtra(EXTRA_REQUEST_ID, -1).takeIf { it >= 0 }
        val requestId = pendingRequestId
        if (requestId != null) {
            val pending = ClassicExtensionWebViewBroker.request(requestId)
            if (pending == null) finish() else showWebRequest(
                title = pending.title,
                target = pending.request,
            ) { ClassicExtensionWebViewBroker.complete(requestId, it) }
            return
        }

        val extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID) ?: return finish()
        lifecycleScope.launch {
            val manager = ClassicExtensionManager.get(this@ClassicExtensionLoginActivity)
            manager.ensureLoaded()
            val client = manager.clientFor(extensionId)
            when (client) {
                is LoginClient.WebView -> showWebRequest(
                    title = "${manager.entries.value.firstOrNull { it.id == extensionId }?.metadata?.name ?: "Extension"} login",
                    target = client.webViewRequest,
                ) { result ->
                    result.onSuccess { users ->
                        if (users != null) manager.saveLoginUsers(extensionId, users)
                    }
                }

                is LoginClient.CustomInput -> showCustomInput(extensionId, client)
                else -> finishWithMessage("This extension does not provide account login")
            }
        }
    }

    private fun showCustomInput(extensionId: String, client: LoginClient.CustomInput) {
        val form = client.forms.firstOrNull()
            ?: return finishWithMessage("This extension did not provide a login form")
        val scale = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * scale).toInt())
        }
        root.addView(TextView(this).apply {
            text = form.label
            textSize = 24f
        })
        val inputs = form.inputFields.associateWith { field ->
            EditText(this).also { input ->
                input.hint = field.label
                input.inputType = when (field.type) {
                    LoginClient.InputField.Type.Email -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    LoginClient.InputField.Type.Password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    LoginClient.InputField.Type.Number -> InputType.TYPE_CLASS_NUMBER
                    LoginClient.InputField.Type.Url -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    else -> InputType.TYPE_CLASS_TEXT
                }
                root.addView(input, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        root.addView(Button(this).apply {
            text = "Sign in"
            setOnClickListener {
                val invalid = inputs.entries.firstOrNull { (field, input) ->
                    val value = input.text?.toString().orEmpty()
                    (field.isRequired && value.isBlank()) ||
                        (value.isNotBlank() && field.regex?.matches(value) == false)
                }
                if (invalid != null) {
                    Toast.makeText(context, "Check ${invalid.key.label}", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        val users = client.onLogin(
                            form.key,
                            inputs.entries.associate { (field, input) ->
                                field.key to input.text?.toString()?.takeIf(String::isNotBlank)
                            },
                        )
                        ClassicExtensionManager.get(this@ClassicExtensionLoginActivity)
                            .saveLoginUsers(extensionId, users)
                    }.onSuccess {
                        withContext(Dispatchers.Main) { finishWithMessage("Account connected") }
                    }.onFailure {
                        withContext(Dispatchers.Main) {
                            isEnabled = true
                            Toast.makeText(context, it.message ?: "Login failed", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun <T> showWebRequest(
        title: String,
        target: EchoWebViewRequest<T>,
        onComplete: suspend (Result<T?>) -> Unit,
    ) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val heading = TextView(this).apply {
            text = title
            textSize = 20f
            setPadding((20 * resources.displayMetrics.density).toInt())
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        val webView = WebView(this)
        root.addView(heading, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(progress, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = if (target.dontCache) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            target.initialUrl.lowerCaseHeaders["user-agent"]?.let { userAgentString = it }
        }

        val intercepted = CopyOnWriteArrayList<NetworkRequest>()
        val completed = AtomicBoolean(false)
        fun inspect(request: NetworkRequest) {
            if (target is EchoWebViewRequest.Headers<*> && target.interceptUrlRegex.matches(request.url)) {
                intercepted += request
            }
            if (target.stopUrlRegex.find(request.url) == null || !completed.compareAndSet(false, true)) return
            lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    when (target) {
                        is EchoWebViewRequest.Headers<*> -> target.onStop(intercepted) as T?
                        is EchoWebViewRequest.Cookie<*> -> {
                            val cookie = CookieManager.getInstance().getCookie(request.url).orEmpty()
                            target.onStop(request, cookie) as T?
                        }
                        is EchoWebViewRequest.Evaluate<*> -> {
                            val value = evaluateJavascript(webView, target.javascriptToEvaluate)
                            target.onStop(request, value) as T?
                        }
                    }
                }
                onComplete(result)
                withContext(Dispatchers.Main) {
                    if (result.isFailure) {
                        Toast.makeText(
                            this@ClassicExtensionLoginActivity,
                            result.exceptionOrNull()?.message ?: "Authentication failed",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    finish()
                }
            }
        }

        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                if (target is EchoWebViewRequest.Evaluate<*>) {
                    target.javascriptToEvaluateOnPageStart?.let { view.evaluateJavascript(it, null) }
                }
                inspect(NetworkRequest(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                inspect(NetworkRequest(url))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                inspect(request.toNetworkRequest())
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                inspect(request.toNetworkRequest())
                return null
            }
        }
        webView.loadUrl(target.initialUrl.url, target.initialUrl.headers)
    }

    private fun WebResourceRequest.toNetworkRequest(): NetworkRequest = NetworkRequest(
        url = url.toString(),
        headers = requestHeaders.orEmpty(),
        method = runCatching { NetworkRequest.Method.valueOf(method) }.getOrDefault(NetworkRequest.Method.GET),
        bodyBase64 = null,
    )

    private suspend fun evaluateJavascript(webView: WebView, javascript: String): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val bridge = JavascriptBridge(
                    onResult = { if (continuation.isActive) continuation.resume(it) },
                    onError = { if (continuation.isActive) continuation.resumeWithException(it) },
                )
                webView.addJavascriptInterface(bridge, "bridge")
                val function = when {
                    javascript.startsWith("async function") -> javascript
                    javascript.startsWith("function") -> "async $javascript"
                    else -> return@suspendCancellableCoroutine continuation.resumeWithException(
                        IllegalArgumentException("Invalid extension JavaScript")
                    )
                }
                webView.evaluateJavascript(
                    "(function(){try{const f=$function;f().then(r=>bridge.result(r)).catch(e=>bridge.error(e.message||e.toString()));}catch(e){bridge.error(e.message||e.toString());}})()",
                    null,
                )
            }
        }

    private class JavascriptBridge(
        private val onResult: (String?) -> Unit,
        private val onError: (Throwable) -> Unit,
    ) {
        @JavascriptInterface fun result(value: String?) = onResult(value)
        @JavascriptInterface fun error(value: String?) = onError(Exception(value ?: "JavaScript failed"))
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        pendingRequestId?.let {
            ClassicExtensionWebViewBroker.complete(
                it,
                Result.failure<Any?>(Exception("Web authentication cancelled")),
            )
        }
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_EXTENSION_ID = "extensionId"
        const val EXTRA_REQUEST_ID = "extensionWebRequestId"
    }
}

object ClassicExtensionWebViewBroker {
    data class Pending(
        val title: String,
        val request: EchoWebViewRequest<Any?>,
        val result: CompletableDeferred<Result<Any?>>,
    )

    private val ids = AtomicInteger(1)
    private val requests = ConcurrentHashMap<Int, Pending>()

    fun client(context: Context, metadata: Metadata): EchoWebViewClient = object : EchoWebViewClient {
        override suspend fun await(
            showWebView: Boolean,
            reason: String,
            request: EchoWebViewRequest<String>,
        ): Result<String?> = awaitRequest(context, metadata, showWebView, reason, request)
    }

    private suspend fun <T> awaitRequest(
        context: Context,
        metadata: Metadata,
        @Suppress("UNUSED_PARAMETER") showWebView: Boolean,
        reason: String,
        request: EchoWebViewRequest<T>,
    ): Result<T?> {
        val id = ids.getAndIncrement()
        @Suppress("UNCHECKED_CAST")
        val pending = Pending("${metadata.name}: $reason", request as EchoWebViewRequest<Any?>, CompletableDeferred())
        requests[id] = pending
        val intent = Intent(context, ClassicExtensionLoginActivity::class.java)
            .putExtra(ClassicExtensionLoginActivity.EXTRA_REQUEST_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ).send()
        return try {
            @Suppress("UNCHECKED_CAST")
            pending.result.await() as Result<T?>
        } finally {
            requests.remove(id)
        }
    }

    fun request(id: Int): Pending? = requests[id]

    fun complete(id: Int, result: Result<*>) {
        @Suppress("UNCHECKED_CAST")
        requests[id]?.result?.complete(result as Result<Any?>)
    }
}
