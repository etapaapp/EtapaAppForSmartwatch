package com.marinov.colegioetapa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
        private const val REQUEST_STORAGE_PERMISSION = 101
        private const val TAG = "MainActivity"
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var pagerAdapter: WatchFragmentPagerAdapter
    private lateinit var viewPagerContainer: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBarsForLegacyDevices()
        setContentView(R.layout.activity_main_watch)

        viewPager = findViewById(R.id.nav_host_fragment)
        viewPagerContainer = findViewById(R.id.view_pager_container)

        pagerAdapter = WatchFragmentPagerAdapter(this)
        viewPager.adapter = pagerAdapter
        // Removido o offscreenPageLimit que carregava todos os fragments
        // O ViewPager2 agora carregará apenas o fragment atual e mantém 1 adjacente por padrão
        viewPager.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT

        handleIntent(intent)

        setupBackPressedHandling()
        solicitarPermissaoNotificacao()
        solicitarPermissaoArmazenamento()
        iniciarNotasWorker()
        iniciarUpdateWorker()
    }

    private fun setupBackPressedHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentItem = viewPager.currentItem
                val backStackCount = supportFragmentManager.backStackEntryCount

                Log.d(TAG, "Back pressed - ViewPager item: $currentItem, backstack: $backStackCount")

                when {
                    backStackCount > 0 -> {
                        Log.d(TAG, "Removendo fragment do backstack")
                        // Fazer pop do backstack PRIMEIRO
                        supportFragmentManager.popBackStack()
                        // Depois restaurar visibilidade das views
                        viewPagerContainer.visibility = View.GONE
                        viewPager.visibility = View.VISIBLE
                        Log.d(TAG, "Views restauradas - ViewPager visível, container modal escondido")
                    }
                    currentItem == 0 -> {
                        Log.d(TAG, "Estamos no Home sem backstack - fechando app")
                        finish()
                    }
                    else -> {
                        Log.d(TAG, "Navegando para Home desde posição: $currentItem")
                        navigateToHome()
                    }
                }
            }
        })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Verificar se é uma notificação de atualização
        if (intent.getBooleanExtra("open_update_directly", false)) {
            val updateUrl = intent.getStringExtra(UpdateCheckWorker.EXTRA_UPDATE_URL)

            // Navegar para o SettingsFragment (posição 5) com os extras
            viewPager.currentItem = 5

            // Passar a URL de atualização para o SettingsFragment
            val settingsFragment = pagerAdapter.getFragmentAtPosition(5) as? SettingsFragment
            settingsFragment?.arguments = Bundle().apply {
                putString(UpdateCheckWorker.EXTRA_UPDATE_URL, updateUrl)
            }
            return
        }

        val destination = intent.getStringExtra("destination") ?: return
        Log.d(TAG, "Handling intent with destination: $destination")

        val position = when (destination) {
            "home" -> 0
            "notas" -> 1
            "horarios" -> 2
            "provas" -> 3
            "more" -> 4
            "settings" -> 5
            else -> 0
        }
        viewPager.currentItem = position
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun configureSystemBarsForLegacyDevices() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val isDarkMode = when (AppCompatDelegate.getDefaultNightMode()) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> {
                    val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    currentNightMode == Configuration.UI_MODE_NIGHT_YES
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.apply {
                    @Suppress("DEPRECATION")
                    clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                        @Suppress("DEPRECATION")
                        statusBarColor = Color.BLACK
                        @Suppress("DEPRECATION")
                        navigationBarColor = Color.BLACK
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            @Suppress("DEPRECATION")
                            var flags = decorView.systemUiVisibility
                            @Suppress("DEPRECATION")
                            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                            @Suppress("DEPRECATION")
                            decorView.systemUiVisibility = flags
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        navigationBarColor = if (isDarkMode) {
                            ContextCompat.getColor(this@MainActivity, R.color.nav_bar_dark)
                        } else {
                            ContextCompat.getColor(this@MainActivity, R.color.nav_bar_light)
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                if (isDarkMode) {
                    @Suppress("DEPRECATION")
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
            if (!isDarkMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
        }
    }

    private fun solicitarPermissaoNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    private fun solicitarPermissaoArmazenamento() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_STORAGE_PERMISSION
                )
            }
        }
    }

    private fun iniciarNotasWorker() {
        val notasWork = PeriodicWorkRequest.Builder(
            NotasWorker::class.java,
            15,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "NotasWorkerTask",
            ExistingPeriodicWorkPolicy.KEEP,
            notasWork
        )
    }

    private fun iniciarUpdateWorker() {
        val updateWork = PeriodicWorkRequest.Builder(
            UpdateCheckWorker::class.java,
            15,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "UpdateCheckWorker",
            ExistingPeriodicWorkPolicy.KEEP,
            updateWork
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_STORAGE_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permissão de armazenamento concedida.")
                } else {
                    Log.d(TAG, "Permissão de armazenamento negada.")
                }
            }
            REQUEST_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Permissão de notificação concedida.")
                } else {
                    Log.d(TAG, "Permissão de notificação negada.")
                }
            }
        }
    }

    fun openCustomFragment(fragment: Fragment) {
        Log.d(TAG, "Abrindo fragment customizado: ${fragment::class.simpleName}")

        // Verificar se já há um fragment no container modal
        val currentFragment = supportFragmentManager.findFragmentById(R.id.view_pager_container)
        if (currentFragment != null) {
            Log.d(TAG, "Fragment existente no container: ${currentFragment::class.simpleName}")
            // Limpar o backstack primeiro
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        viewPagerContainer.visibility = View.VISIBLE
        viewPager.visibility = View.GONE

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in, android.R.anim.fade_out,
                android.R.anim.fade_in, android.R.anim.fade_out
            )
            .replace(R.id.view_pager_container, fragment)
            .addToBackStack(null)
            .commit()

        Log.d(TAG, "Fragment adicionado ao backstack. Total de itens: ${supportFragmentManager.backStackEntryCount + 1}")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsFragment::class.java))
                true
            }
            R.id.action_profile -> {
                viewPager.currentItem = 4
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun abrirDetalhesProva(url: String) {
        val fragment = DetalhesProvaFragment.newInstance(url)
        openCustomFragment(fragment)
    }

    fun navigateToHome() {
        Log.d(TAG, "Navegando para Home")
        if (supportFragmentManager.backStackEntryCount > 0) {
            viewPagerContainer.visibility = View.GONE
            viewPager.visibility = View.VISIBLE
            supportFragmentManager.popBackStack()
        }
        viewPager.currentItem = 0
    }

    fun onGlobalLoginSuccess() {
        Log.d(TAG, "Login global bem-sucedido. Notificando fragments para recarregar.")
        // A chamada para navigateToHome() foi removida.
        // Ela era redundante, pois o fluxo de login é sempre iniciado a partir do HomeFragment,
        // o que significa que já estamos na tela principal. Em alguns casos, essa chamada
        // pode causar um ciclo de recarregamento que interfere com a sincronização de
        // cookies pós-login, levando a um loop.
        notifyLoginSuccessToFragments()
    }

    private fun notifyLoginSuccessToFragments() {
        // Notifica apenas os fragments que estão atualmente carregados
        pagerAdapter.getLoadedFragments().forEach { fragment ->
            if (fragment is LoginStateListener) {
                Log.d(TAG, "Notificando ${fragment.javaClass.simpleName} sobre o login.")
                fragment.onLoginSuccess()
            }
        }
    }

    inner class WatchFragmentPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        private val fragments = mutableMapOf<Int, Fragment>()

        override fun getItemCount(): Int = 6

        override fun createFragment(position: Int): Fragment {
            Log.d(TAG, "Criando fragment para posição: $position")
            return fragments[position] ?: when (position) {
                0 -> HomeFragment()
                1 -> NotasFragment()
                2 -> HorariosAula()
                3 -> CalendarioProvas()
                4 -> MoreFragment()
                5 -> SettingsFragment()
                else -> HomeFragment()
            }.also {
                fragments[position] = it
            }
        }

        // Método para obter apenas os fragments que foram carregados
        fun getLoadedFragments(): List<Fragment> {
            return fragments.values.toList()
        }

        // Método para obter um fragment em uma posição específica
        fun getFragmentAtPosition(position: Int): Fragment? {
            return fragments[position]
        }

    }

    interface LoginStateListener {
        fun onLoginSuccess()
    }
}