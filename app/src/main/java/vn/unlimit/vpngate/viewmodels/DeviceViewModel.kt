package vn.unlimit.vpngate.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.common.base.Strings
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.unlimit.vpngate.api.DeviceApiService
import vn.unlimit.vpngate.models.DeviceInfo
import vn.unlimit.vpngate.utils.PaidServerUtil

class DeviceViewModel(application: Application) : BaseViewModel(application) {
    val deviceApiService: DeviceApiService = retrofit.create(DeviceApiService::class.java)
    var deviceInfo: MutableLiveData<DeviceInfo> = MutableLiveData(getDeviceInfo())

    companion object {
        const val TAG = "DeviceViewModel"
        const val DEVICE_INFO_KEY = "DEVICE_INFO_KEY"
    }

    /**
     * Device push registration has been removed together with Firebase Cloud
     * Messaging (Google service). This call is now a no-op.
     */
    fun addDevice() {
        Log.d(TAG, "addDevice skipped: push notifications (Firebase Cloud Messaging) are disabled")
    }

    fun getNotificationSetting() {
        if (deviceInfo.value == null) {
            Log.e(TAG, "No device information")
            return
        }
        isLoading.value = true
        viewModelScope.launch {
            try {
                val deviceInfo = deviceApiService.getNotificationSetting(deviceInfo.value!!._id)
                withContext(Dispatchers.Main) {
                    setDeviceInfo(deviceInfo)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Got exception when get notification setting", e)
            } finally {
                isLoading.postValue(false)
            }
        }
    }

    fun setNotificationSetting(isEnableNotification: Boolean) {
        if (deviceInfo.value == null) {
            Log.e(TAG, "No device information")
            return
        }
        if (deviceInfo.value!!.notificationSetting?.data == isEnableNotification) {
            Log.w(TAG, "Device notification change same as current setting. Skip update API")
            return
        }
        isLoading.value = true
        val notificationSetting = DeviceInfo.NotificationSetting()
        notificationSetting.data = isEnableNotification
        viewModelScope.launch {
            try {
                val setNotificationSettingResponse = deviceApiService.setNotificationSetting(
                    deviceInfo.value!!._id,
                    notificationSetting
                )
                if (setNotificationSettingResponse.saved) {
                    withContext(Dispatchers.Main) {
                        setDeviceInfo(setNotificationSettingResponse.userDevice)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Got exception when set notification setting", e)
            } finally {
                isLoading.postValue(false)
            }
        }
    }

    private fun setDeviceInfo(dInfo: DeviceInfo) {
        paidServerUtil.setStringSetting(DEVICE_INFO_KEY, paidServerUtil.gson.toJson(dInfo))
        deviceInfo.value = dInfo
    }

    private fun getDeviceInfo(): DeviceInfo? {
        try {
            val json = paidServerUtil.getStringSetting(DEVICE_INFO_KEY)
            if (Strings.isNullOrEmpty(json)) {
                return null
            }
            return paidServerUtil.gson.fromJson(json, object : TypeToken<DeviceInfo>() {}.type)
        } catch (th: Throwable) {
            Log.d(TAG, "Got exception on getDeviceInfo", th)
        }
        return null
    }
}