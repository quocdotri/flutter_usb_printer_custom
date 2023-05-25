package app.mylekha.client.flutter_usb_printer.adapter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.nio.charset.Charset
import java.util.*
import io.flutter.plugin.common.MethodChannel.Result
import java.nio.ByteBuffer;


class USBPrinterAdapter {

    private var mInstance: USBPrinterAdapter? = null


    private val LOG_TAG = "Flutter USB Printer"
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDevices = mutableListOf<String>()
    private var mUsbDeviceConnection = mutableMapOf<String, UsbDeviceConnection?>()
    private var mEndPoint = mutableMapOf<String,UsbEndpoint?>()

    private val ACTION_USB_PERMISSION = "app.mylekha.client.flutter_usb_printer.USB_PERMISSION"
    var usbPermissionResultFlutterCallback: Result? = null // for usbPermisison



    fun getInstance(): USBPrinterAdapter? {
        if (mInstance == null) {
            mInstance = this;
        }
        return mInstance
    }

    private val mUsbDeviceReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_USB_PERMISSION == action) {
                synchronized(this) {
                    val usbDevice =
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        Log.i(
                            LOG_TAG,
                            "Success to grant permission for device " + usbDevice!!.deviceName + ", vendor_id: " + usbDevice.vendorId + " product_id: " + usbDevice.productId
                        )
                        mUsbDevice = usbDevice
                        usbPermissionResultFlutterCallback?.success(true)
                    } else {
                        Toast.makeText(
                            context,
                            "User refused to give USB device permissions" + usbDevice!!.deviceName,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                val newUsbDeviceListInString = getDeviceListInString()
                val disconnectedDevices = mUsbDevices.filterNot { it in newUsbDeviceListInString }
                Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG).show()
                disconnectedDevices.forEach {
                    Log.i(LOG_TAG, "$it has disconnected")
                    if (mUsbDevice != null && getUsbDeviceString(mUsbDevice!!) == it) {
                        mUsbDevice = null
                    }
                    mUsbDeviceConnection.remove(it)
                    mEndPoint.remove(it)
                }
                mUsbDevices = getDeviceListInString()
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        mPermissionIndent =
            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        mUsbDevices = getDeviceListInString()
        Log.v(LOG_TAG, "USB Printer initialized")
    }

    fun getDeviceList(): List<UsbDevice> {
        if (mUSBManager == null) {
            Toast.makeText(
                mContext,
                "USB Manager is not initialized while get device list",
                Toast.LENGTH_LONG
            ).show()
            return emptyList()
        }
        return ArrayList(mUSBManager!!.deviceList.values)
    }

    fun getDeviceListInString(): MutableList<String> {
        if (mUSBManager == null) {
            Toast.makeText(
                mContext,
                "USB Manager is not initialized while get device list",
                Toast.LENGTH_LONG
            ).show()
            return mutableListOf<String>()
        }
        return mUSBManager!!.deviceList.values.map{getUsbDeviceString(it)}.toMutableList<String>()
    }

    fun requestUsbPermission(vendorId: Int, productId: Int, deviceName: String?, manufacturerName: String?, result: Result): Boolean {
      usbPermissionResultFlutterCallback = result
      val usbDevices = getDeviceList().filter{it.vendorId == vendorId && it.productId == productId }
      mUsbDevices = getDeviceListInString()
      // if no device, return false
      if (usbDevices.size <= 0) {
        return false
      }
      
      var filteredByManufactureName  = false
      var filteredDeviceName = false

      // Adds more filter condition, by manufacture names or device names in case
      // there are multiple devices with same productId and vendorId connected to the android.
      var targetUsbDevice = usbDevices[0]
      val usbFilteredByManufactureNames = usbDevices.filter{ it.manufacturerName == manufacturerName}
      if (usbFilteredByManufactureNames.size > 0) {
        targetUsbDevice = usbFilteredByManufactureNames[0]
        filteredByManufactureName = true
        val usbFilterByDeviceNames = usbFilteredByManufactureNames.filter{ it.deviceName == deviceName}
        if (usbFilterByDeviceNames.size > 0) {
          targetUsbDevice = usbFilterByDeviceNames[0]
          filteredDeviceName = true
        }
      }
      
      // Check if targetUsb is same as current connected usb device or not
      var isSameDevice = false
      if (mUsbDevice != null) {
        isSameDevice = true
        if (mUsbDevice!!.vendorId != vendorId || mUsbDevice!!.productId != productId) {
          isSameDevice = false
        } else if (filteredByManufactureName && mUsbDevice!!.manufacturerName != manufacturerName) {
          isSameDevice = false
        } else if (filteredDeviceName && mUsbDevice!!.deviceName != deviceName) {
          isSameDevice = false
        }
      } 

      if (isSameDevice) {
        result.success(true)
        return true
      }
      Log.v(
        LOG_TAG,
        "Request for device: vendor_id: " + targetUsbDevice.vendorId + ", product_id: " + targetUsbDevice.productId +
          ", device_name: " + targetUsbDevice.deviceName  + ", manufacturer_name: " + targetUsbDevice.manufacturerName
      )
      mUSBManager!!.requestPermission(targetUsbDevice, mPermissionIndent)
      mUsbDevice = targetUsbDevice
      return true
    }
    
    fun openConnection(): Boolean {
        if (mUsbDevice == null) {
            Log.e(LOG_TAG, "USB Deivce is not initialized")
            return false
        }
        if (mUSBManager == null) {
            Log.e(LOG_TAG, "USB Manager is not initialized")
            return false
        }
        if (mUsbDeviceConnection[getUsbDeviceString(mUsbDevice!!)] != null) {
            Log.i(LOG_TAG, "USB Connection already connected")
            return true
        }
        val usbInterface = mUsbDevice!!.getInterface(0)
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    val usbDeviceConnection = mUSBManager!!.openDevice(mUsbDevice)
                    if (usbDeviceConnection == null) {
                        Log.e(LOG_TAG, "failed to open USB Connection")
                        return false
                    }
                    Toast.makeText(mContext, "Device connected", Toast.LENGTH_SHORT).show()
                    return if (usbDeviceConnection.claimInterface(usbInterface, true)) {
                        mEndPoint.put(getUsbDeviceString(mUsbDevice!!), ep)
                        mUsbDeviceConnection.put(getUsbDeviceString(mUsbDevice!!), usbDeviceConnection)
                        return true
                    } else {
                        usbDeviceConnection.close()
                        Log.e(LOG_TAG, "failed to claim usb connection")
                        return false
                    }
                }
            }
        }
        return true
    }

    fun printText(text: String): Boolean {
        Log.v(LOG_TAG, "start to print text")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                val bytes = text.toByteArray(Charset.forName("UTF-8"))
                val b = mUsbDeviceConnection[getUsbDeviceString(mUsbDevice!!)]!!.bulkTransfer(mEndPoint[getUsbDeviceString(mUsbDevice!!)], bytes, bytes.size, 100000)
                Log.i(LOG_TAG, "Return Status: b-->$b")
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "failed to connected to device")
            false
        }
    }

    fun printRawText(data: String): Boolean {
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val b = mUsbDeviceConnection[getUsbDeviceString(mUsbDevice!!)]!!.bulkTransfer(mEndPoint[getUsbDeviceString(mUsbDevice!!)], bytes, bytes.size, 100000)
                Log.i(LOG_TAG, "Return Status: $b")
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "failed to connected to device")
            false
        }
    }

    fun write(bytes: ByteArray): Boolean {
        Log.v(LOG_TAG, "start to print raw data $bytes")
        val isConnected = openConnection()
        return if (isConnected) {
            Log.v(LOG_TAG, "Connected to device")
            Thread {
                val buffer = ByteBuffer.allocate(bytes.size)
                buffer.put(bytes)
                val usbRequest = UsbRequest()
                try {
                    usbRequest.initialize(mUsbDeviceConnection[getUsbDeviceString(mUsbDevice!!)]!!, 
                        mEndPoint[getUsbDeviceString(mUsbDevice!!)],
                    );
                    if (!usbRequest.queue(buffer, bytes.size)) {
                        false
                    }
                    mUsbDeviceConnection[getUsbDeviceString(mUsbDevice!!)]!!.requestWait()
                } finally {
                    usbRequest.close()
                }
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "failed to connected to device")
            false
        }
    }

    fun getUsbDeviceString(usbDevice: UsbDevice): String {
        return "${usbDevice.vendorId}-${usbDevice.productId}-${usbDevice.manufacturerName}-${usbDevice.deviceName}"
    }
}
