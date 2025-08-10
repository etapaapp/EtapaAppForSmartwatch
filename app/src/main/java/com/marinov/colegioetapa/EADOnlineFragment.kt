package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton

class EADOnlineFragment : Fragment() {

    private companion object {
        const val AUTH_CHECK_URL = "https://areaexclusiva.colegioetapa.com.br/provas/notas"
        const val TARGET_URL = "https://areaexclusiva.colegioetapa.com.br/ead/"
    }

    private lateinit var layoutSemInternet: LinearLayout
    private lateinit var btnTentarNovamente: MaterialButton
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_fullscreen_placeholder, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews(view)
        setupBackPressHandler()
        checkInternetAndAuthentication()
    }

    private fun setupViews(view: View) {
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)

        btnTentarNovamente.setOnClickListener {
            navigateToHomeFragment()
        }
    }

    private fun setupBackPressHandler() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                navigateToHomeFragment()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }

    private fun checkInternetAndAuthentication() {
        if (!hasInternetConnection()) {
            showNoInternetUI()
            return
        }

        performAuthCheck()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun performAuthCheck() {
        val authWebView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }

        authWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    "(function() { " +
                            "return document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > " +
                            "div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(2) > div.card-body > table') !== null; " +
                            "})();"
                ) { value ->
                    val isAuthenticated = value == "true"
                    if (isAuthenticated) {
                        startWebViewActivityAndPop()
                    } else {
                        showNoInternetUI()
                    }
                    authWebView.destroy()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                showNoInternetUI()
                authWebView.destroy()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                showNoInternetUI()
                authWebView.destroy()
            }
        }
        authWebView.loadUrl(AUTH_CHECK_URL)
    }

    private fun startWebViewActivityAndPop() {
        WebViewActivity.start(requireContext(), TARGET_URL)
        view?.post {
            if (isAdded && !parentFragmentManager.isDestroyed && !parentFragmentManager.isStateSaved) {
                try {
                    parentFragmentManager.popBackStackImmediate()
                } catch (_: IllegalStateException) {
                    parentFragmentManager.executePendingTransactions()
                    if (parentFragmentManager.backStackEntryCount > 0) {
                        parentFragmentManager.popBackStackImmediate()
                    }
                }
            }
        }
    }

    private fun showNoInternetUI() {
        handler.post {
            layoutSemInternet.visibility = View.VISIBLE
        }
    }

    private fun hasInternetConnection(): Boolean {
        val context = context ?: return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun navigateToHomeFragment() {
        (activity as? MainActivity)?.navigateToHome()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }
}