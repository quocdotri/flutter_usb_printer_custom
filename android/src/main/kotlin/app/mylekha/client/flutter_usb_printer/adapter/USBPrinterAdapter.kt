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


class USBPrinterAdapter {

    private var mInstance: USBPrinterAdapter? = null


    private val LOG_TAG = "Flutter USB Printer"
    private var mContext: Context? = null
    private var mUSBManager: UsbManager? = null
    private var mPermissionIndent: PendingIntent? = null
    private var mUsbDevice: UsbDevice? = null
    private var mUsbDeviceConnection = mutableMapOf<UsbDevice, UsbDeviceConnection?>()
    private var mUsbInterface = mutableMapOf<UsbDevice, UsbInterface?>()
    private var mEndPoint = mutableMapOf<UsbDevice,UsbEndpoint?>()

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
                if (mUsbDevice != null) {
                    Toast.makeText(context, "USB device has been turned off", Toast.LENGTH_LONG)
                        .show()
                    //closeConnectionIfExists()
                }
            }
        }
    }

    fun init(reactContext: Context?) {
        mContext = reactContext
        mUSBManager = mContext!!.getSystemService(Context.USB_SERVICE) as UsbManager
        mPermissionIndent =
            PendingIntent.getBroadcast(mContext, 0, Intent(ACTION_USB_PERMISSION), 0)
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        mContext!!.registerReceiver(mUsbDeviceReceiver, filter)
        Log.v(LOG_TAG, "USB Printer initialized")
    }


    fun closeConnectionIfExists() {
        mUsbDeviceConnection.map{
            if (mUsbInterface[it.key] != null) {
                it.value?.releaseInterface(mUsbInterface[it.key]!!)
            }
            it.value?.close()
        }
        mUsbDeviceConnection = mutableMapOf<UsbDevice, UsbDeviceConnection?>()
        mUsbInterface = mutableMapOf<UsbDevice, UsbInterface?>()
        mEndPoint = mutableMapOf<UsbDevice,UsbEndpoint?>()
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

    fun requestUsbPermission(vendorId: Int, productId: Int, deviceName: String?, manufacturerName: String?, result: Result): Boolean {
      usbPermissionResultFlutterCallback = result
      val usbDevices = getDeviceList().filter{it.vendorId == vendorId && it.productId == productId }
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
      //closeConnectionIfExists()
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
        if (mUsbDeviceConnection[mUsbDevice!!] != null) {
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
                        mEndPoint.put(mUsbDevice!!, ep)
                        mUsbInterface.put(mUsbDevice!!, usbInterface) 
                        mUsbDeviceConnection.put(mUsbDevice!!, usbDeviceConnection)
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
                val b = mUsbDeviceConnection[mUsbDevice!!]!!.bulkTransfer(mEndPoint[mUsbDevice!!], bytes, bytes.size, 100000)
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
                val b = mUsbDeviceConnection[mUsbDevice!!]!!.bulkTransfer(mEndPoint[mUsbDevice!!], bytes, bytes.size, 100000)
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
                val b = mUsbDeviceConnection[mUsbDevice!!]!!.bulkTransfer(mEndPoint[mUsbDevice!!], bytes, bytes.size, 100000)
                Log.i(LOG_TAG, "Return Status: $b")
            }.start()
            true
        } else {
            Log.v(LOG_TAG, "failed to connected to device")
            false
        }
    }
}
