package com.kavabanga.signage.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fetches Firestore data via a hidden WebView loading the actual Firebase Hosting page.
 * The WebView loads a real URL from kava-signage-2026.web.app — same origin as the web panel.
 */
class FirestoreWebBridge(private val context: Context) {

    companion object {
        private const val TAG = "FirestoreWebBridge"
        private const val TIMEOUT_MS = 25_000L
        // The actual hosted web app URL — goes through Fastly CDN, not googleapis.com
        private const val BASE_URL = "https://kava-signage-2026.web.app"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun fetchCollection(
        collectionPath: String,
        onSuccess: (List<Map<String, Any?>>) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            doFetch(
                "db.collection('$collectionPath').get()",
                onSuccess, onError
            )
        }
    }

    fun fetchSubcollection(
        collection: String, docId: String, subcollection: String,
        onSuccess: (List<Map<String, Any?>>) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            doFetch(
                "db.collection('$collection').doc('$docId').collection('$subcollection').get()",
                onSuccess, onError
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun doFetch(
        jsQuery: String,
        onSuccess: (List<Map<String, Any?>>) -> Unit,
        onError: (String) -> Unit
    ) {
        var completed = false
        fun finish() { completed = true }

        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            settings.allowUniversalAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            settings.allowFileAccessFromFileURLs = true
        }

        val bridge = object {
            @JavascriptInterface
            fun onResult(json: String) {
                if (completed) return; finish()
                mainHandler.post {
                    try {
                        onSuccess(parseJsonArray(json))
                    } catch (e: Exception) {
                        Log.e(TAG, "Parse error", e)
                        onError("Parse: ${e.message}")
                    }
                    wv.destroy()
                }
            }
            @JavascriptInterface
            fun onError(msg: String) {
                if (completed) return; finish()
                mainHandler.post {
                    Log.e(TAG, "JS error: $msg")
                    onError(msg)
                    wv.destroy()
                }
            }
            @JavascriptInterface
            fun onLog(msg: String) {
                Log.d(TAG, "JS: $msg")
            }
        }

        wv.addJavascriptInterface(bridge, "Android")

        // Forward JS console to logcat
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.d(TAG, "Console: ${msg?.message()}")
                return true
            }
        }

        // Timeout
        val timeoutRunnable = Runnable {
            if (!completed) {
                finish()
                onError("Timeout ${TIMEOUT_MS / 1000}s")
                wv.destroy()
            }
        }
        mainHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

        // Load the actual web app page, then inject our query
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (completed) return
                Log.d(TAG, "Page loaded: $url — injecting query")

                // Wait for Firebase to init, then execute query
                val js = """
                (function() {
                    Android.onLog('Script started, checking firebase...');
                    
                    function waitForFirebase(attempts) {
                        if (attempts <= 0) {
                            Android.onError('Firebase not available after retries');
                            return;
                        }
                        
                        if (typeof firebase === 'undefined' || !firebase.firestore) {
                            Android.onLog('Firebase not ready, retry ' + attempts);
                            setTimeout(function() { waitForFirebase(attempts - 1); }, 500);
                            return;
                        }
                        
                        Android.onLog('Firebase ready, querying...');
                        var db = firebase.firestore();
                        
                        $jsQuery.then(function(snap) {
                            Android.onLog('Query OK: ' + snap.size + ' docs');
                            var docs = [];
                            snap.forEach(function(doc) {
                                var data = doc.data();
                                for (var k in data) {
                                    if (data[k] && data[k].toDate) {
                                        data[k] = data[k].toDate().toISOString();
                                    }
                                    if (Array.isArray(data[k])) {
                                        data[k] = data[k].map(function(item) {
                                            if (item && typeof item === 'object') {
                                                for (var ik in item) {
                                                    if (item[ik] && item[ik].toDate) {
                                                        item[ik] = item[ik].toDate().toISOString();
                                                    }
                                                }
                                            }
                                            return item;
                                        });
                                    }
                                }
                                data.__id__ = doc.id;
                                docs.push(data);
                            });
                            Android.onResult(JSON.stringify(docs));
                        }).catch(function(err) {
                            Android.onError('Query failed: ' + (err.message || err));
                        });
                    }
                    
                    waitForFirebase(20);
                })();
                """.trimIndent()

                view?.evaluateJavascript(js, null)
            }
        }

        // Load the REAL index page — it already includes Firebase SDK and config
        Log.d(TAG, "Loading $BASE_URL/index.html")
        wv.loadUrl("$BASE_URL/index.html")
    }

    private fun parseJsonArray(json: String): List<Map<String, Any?>> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { toMap(arr.getJSONObject(it)) }
    }

    private fun toMap(obj: JSONObject): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        for (k in obj.keys()) {
            m[k] = when (val v = obj.get(k)) {
                is JSONObject -> toMap(v)
                is JSONArray -> (0 until v.length()).map {
                    when (val item = v.get(it)) {
                        is JSONObject -> toMap(item)
                        JSONObject.NULL -> null
                        else -> item
                    }
                }
                JSONObject.NULL -> null
                else -> v
            }
        }
        return m
    }
}
