package ir.am3n.rtsp.client.demo

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import ir.am3n.rtsp.client.Rtsp

@SuppressLint("LogNotTimber")
class LiveViewModel : ViewModel() {

    companion object {
        private const val TAG: String = "LiveViewModel"
        private const val RTSP_REQUEST_KEY = "rtsp_request"
        private const val DEFAULT_RTSP_REQUEST = "rtsp://192.168.1.2:554/11"
        private const val LIVE_PARAMS_FILENAME = "live_params"
    }

    val rtspRequest = MutableLiveData(DEFAULT_RTSP_REQUEST)

    fun loadParams(context: Context) {
        if (Rtsp.DEBUG) Log.v(TAG, "loadParams()")
        val pref = context.getSharedPreferences(LIVE_PARAMS_FILENAME, Context.MODE_PRIVATE)
        try {
            rtspRequest.setValue(pref.getString(RTSP_REQUEST_KEY, DEFAULT_RTSP_REQUEST))
        } catch (e: ClassCastException) {
            e.printStackTrace()
        }
    }

    fun saveParams(context: Context) {
        if (Rtsp.DEBUG) Log.v(TAG, "saveParams()")
        val editor = context.getSharedPreferences(LIVE_PARAMS_FILENAME, Context.MODE_PRIVATE).edit()
        editor.putString(RTSP_REQUEST_KEY, rtspRequest.value)
        editor.apply()
    }

}