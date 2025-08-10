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

class MainActivity : AppCompatActivity(), WebViewFragment.LoginSuccessListener {

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 100
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

        solicitarPermissaoNotificacao()
        iniciarNotasWorker()
        iniciarUpdateWorker()
    }

    override fun onLoginSuccess() {
        Log.d(TAG, "Login success callback - current item: ${viewPager.currentItem}")
        if (viewPager.currentItem == 0) {
            (pagerAdapter.getFragment(0) as? HomeFragment)?.onLoginSuccess()
        }
        // Fechar o WebView fragment se estiver aberto
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val destination = intent?.getStringExtra("destination") ?: return
        Log.d(TAG, "Handling intent with destination: $destination")
        val position = when (destination) {
            "home" -> 0
            "notas" -> 1
            "horarios" -> 2
            "provas" -> 3
            "profile" -> 4
            "more" -> 5
            "settings" -> 6
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
    }

    fun openCustomFragment(fragment: Fragment) {
        Log.d(TAG, "Abrindo fragment customizado: ${fragment::class.simpleName}")

        // Mostrar o container e o ViewPager
        viewPagerContainer.visibility = View.VISIBLE
        viewPager.visibility = View.GONE

        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out,
                android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.view_pager_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            Log.d(TAG, "Voltando do fragment customizado")
            viewPagerContainer.visibility = View.GONE
            viewPager.visibility = View.VISIBLE
            supportFragmentManager.popBackStack()
        } else {
            super.onBackPressed()
        }
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
        // Se há fragment no backstack, remove
        if (supportFragmentManager.backStackEntryCount > 0) {
            viewPagerContainer.visibility = View.GONE
            viewPager.visibility = View.VISIBLE
            supportFragmentManager.popBackStack()
        }
        // Navega para a aba Home
        viewPager.currentItem = 0
    }

    inner class WatchFragmentPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        private val fragments = mutableMapOf<Int, Fragment>()

        override fun getItemCount(): Int = 7

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> HomeFragment()
                1 -> NotasFragment()
                2 -> HorariosAula()
                3 -> CalendarioProvas()
                4 -> ProfileFragment()
                5 -> MoreFragment()
                6 -> SettingsFragment()
                else -> HomeFragment()
            }.also {
                fragments[position] = it
            }
        }

        fun getFragment(position: Int): Fragment? {
            return fragments[position]
        }
    }
}