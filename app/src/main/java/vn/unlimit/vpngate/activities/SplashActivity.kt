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
import vn.unlimit.vpngate.App
import vn.unlimit.vpngate.activities.paid.ActivateActivity
import vn.unlimit.vpngate.activities.paid.LoginActivity
import vn.unlimit.vpngate.activities.paid.PaidServerActivity
import vn.unlimit.vpngate.activities.paid.ResetPassActivity
import vn.unlimit.vpngate.databinding.ActivitySplashBinding
import vn.unlimit.vpngate.provider.PaidServerProvider
import vn.unlimit.vpngate.utils.PaidServerUtil
import java.util.regex.Pattern

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SplashActivity"
        private const val ACTIVATE_URL_REGEX = "/user/(\\w{24})/activate/(\\w{32})"
        private const val PASS_RESET_URL_REGEX = "/user/password-reset/(\\w{20})"
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
        checkDynamicLink()
    }

    private fun checkAppUpdateAndStartActivity() {
        startStartUpActivity()
    }

    private fun checkAppUpdateAndStartActivityWithDelay(delay: Long = 2000) {
        Handler(Looper.getMainLooper()).postDelayed({
            checkAppUpdateAndStartActivity()
        }, delay)
    }

    fun startStartUpActivity(delay: Long = 100) {
        val paidServerUtil: PaidServerUtil = App.instance!!.paidServerUtil!!
        val actIntent: Intent =
            if (paidServerUtil.getStartUpScreen() == PaidServerUtil.StartUpScreen.PAID_SERVER) {
                if (paidServerUtil.isLoggedIn()) {
                    Intent(this, PaidServerActivity::class.java)
                } else {
                    Intent(this, LoginActivity::class.java)
                }
            } else {
                Intent(this, MainActivity::class.java)
            }
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(actIntent)
            finish()
        }, delay)
    }

    private fun redirectDeepLink(deepLink: String) {
        val matcherActivate = Pattern.compile(ACTIVATE_URL_REGEX).matcher(deepLink)
        if (matcherActivate.find()) {
            val userId = matcherActivate.group(1)
            val activateCode = matcherActivate.group(2)
            val intentActivate = Intent(this, ActivateActivity::class.java)
            intentActivate.putExtra(PaidServerProvider.USER_ID, userId)
            intentActivate.putExtra(PaidServerProvider.ACTIVATE_CODE, activateCode)
            startActivity(intentActivate)
            finish()
            return
        }
        val matcherResetPass = Pattern.compile(PASS_RESET_URL_REGEX).matcher(deepLink)
        if (matcherResetPass.find()) {
            val token = matcherResetPass.group(1)
            val intentResetPass = Intent(this, ResetPassActivity::class.java)
            intentResetPass.putExtra(PaidServerProvider.RESET_PASS_TOKEN, token)
            startActivity(intentResetPass)
            finish()
            return
        }
        Log.d(TAG, "Deep link %s does not match any regex. Go to home".format(deepLink))
        checkAppUpdateAndStartActivityWithDelay()
    }

    private fun checkDynamicLink() {
        val action: String? = intent.action
        val deepLink: String? = intent.data?.toString()
        if (action?.equals("android.intent.action.VIEW") == true && deepLink != null && deepLink.contains(
                "https://app."
            )
        ) {
            Log.d(TAG, "Got action %s with url %s".format(action, deepLink))
            redirectDeepLink(deepLink.toString())
            return
        }
        Log.d(TAG, "Start app normal because of no deeplink")
        checkAppUpdateAndStartActivityWithDelay()
    }
}