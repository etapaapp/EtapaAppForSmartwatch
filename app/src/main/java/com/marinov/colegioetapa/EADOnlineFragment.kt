package com.marinov.colegioetapa

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.activity.result.contract.ActivityResultContracts
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
    private var isAuthenticating = false

    // Registrar o launcher para receber resultado da WebViewActivity
    private val webViewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Quando a WebView retorna (independente do resultado), volta para home
        handler.postDelayed({
            if (isAdded && !isDetached) {
                navigateToHomeFragment()
            }
        }, 100) // Pequeno delay para suavizar a transição
    }

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

        // Só iniciar autenticação se não estiver já fazendo
        if (!isAuthenticating) {
            checkInternetAndAuthentication()
        }
    }

    private fun setupViews(view: View) {
        layoutSemInternet = view.findViewById(R.id.layout_sem_internet)
        btnTentarNovamente = view.findViewById(R.id.btn_tentar_novamente)

        btnTentarNovamente.setOnClickListener {
            if (!isAuthenticating) {
                // Tentar novamente a autenticação
                layoutSemInternet.visibility = View.GONE
                checkInternetAndAuthentication()
            }
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

        isAuthenticating = true
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
                super.onPageFinished(view, url)

                // Dar um tempo para a página carregar completamente
                handler.postDelayed({
                    if (isAdded && !isDetached) {
                        view?.evaluateJavascript(
                            "(function() { " +
                                    "return document.querySelector('#page-content-wrapper > div.d-lg-flex > div.container-fluid.p-3 > " +
                                    "div.card.bg-transparent.border-0 > div.card-body.px-0.px-md-3 > div:nth-child(2) > div.card-body > table') !== null; " +
                                    "})();"
                        ) { value ->
                            if (isAdded && !isDetached) {
                                val isAuthenticated = value == "true"
                                if (isAuthenticated) {
                                    startWebViewActivity()
                                } else {
                                    isAuthenticating = false
                                    showNoInternetUI()
                                }

                                // Limpar WebView
                                try {
                                    authWebView.destroy()
                                } catch (e: Exception) {
                                    // Ignorar erros de destruição
                                }
                            }
                        }
                    }
                }, 1000) // Aguardar 1 segundo para garantir que a página carregou
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (isAdded && !isDetached) {
                    isAuthenticating = false
                    showNoInternetUI()
                    try {
                        authWebView.destroy()
                    } catch (e: Exception) {
                        // Ignorar erros de destruição
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (isAdded && !isDetached) {
                    isAuthenticating = false
                    showNoInternetUI()
                    try {
                        authWebView.destroy()
                    } catch (e: Exception) {
                        // Ignorar erros de destruição
                    }
                }
            }
        }

        authWebView.loadUrl(AUTH_CHECK_URL)
    }

    private fun startWebViewActivity() {
        try {
            if (!isAdded || isDetached) return

            // Criar Intent para WebViewActivity
            val intent = Intent(requireContext(), WebViewActivity::class.java).apply {
                putExtra(WebViewActivity.EXTRA_URL, TARGET_URL)
            }

            // Usar o launcher para iniciar a WebViewActivity e aguardar retorno
            webViewLauncher.launch(intent)

            isAuthenticating = false

        } catch (e: Exception) {
            // Em caso de erro, volta para home
            isAuthenticating = false
            navigateToHomeFragment()
        }
    }

    private fun showNoInternetUI() {
        if (isAdded && !isDetached) {
            handler.post {
                try {
                    layoutSemInternet.visibility = View.VISIBLE
                } catch (e: Exception) {
                    // Ignorar erros de UI
                }
            }
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
        handler.post {
            if (isAdded && !isDetached) {
                try {
                    (activity as? MainActivity)?.navigateToHome()
                } catch (e: Exception) {
                    // Ignorar erros de navegação
                }
            }
        }
    }

    override fun onDestroyView() {
        isAuthenticating = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    override fun onDetach() {
        isAuthenticating = false
        super.onDetach()
    }
}