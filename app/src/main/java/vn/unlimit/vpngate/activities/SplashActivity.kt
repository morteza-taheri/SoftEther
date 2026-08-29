package vn.unlimit.vpngate.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import vn.unlimit.vpngate.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SplashActivity"
    }

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val initialLoadingBottom = binding.txtLoadingText.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.txtLoadingText.updatePadding(bottom = initialLoadingBottom + insets.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(binding.root)
        launchMain()
    }

    fun launchMain() {
        Handler(Looper.getMainLooper()).postDelayed({
            val actIntent = Intent(this, MainActivity::class.java)
            startActivity(actIntent)
            finish()
        }, 250)
    }
}