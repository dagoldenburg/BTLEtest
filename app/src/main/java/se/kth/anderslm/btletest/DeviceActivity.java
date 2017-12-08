package se.kth.anderslm.btletest;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.UUID;

/**
 * This is where we manage the BLE device and the corresponding services, characteristics et c.
 * <p>
 * NB: In this simple example there is no other way to turn off notifications than to
 * leave the activity (the BluetoothGatt is disconnected and closed in activity.onStop).
 */
public class DeviceActivity extends AppCompatActivity {

    /**
     * Documentation on UUID:s and such for services on a BBC Micro:bit.
     * Characteristics et c. are found at
     * https://lancaster-university.github.io/microbit-docs/resources/bluetooth/bluetooth_profile.html
     */
    // UART service and characteristics
    public static UUID UARTSERVICE_SERVICE_UUID =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID UART_RX_CHARACTERISTIC_UUID = // receiver on Micro:bit
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID UART_TX_CHARACTERISTIC_UUID = // transmitter on Micro:bit
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

    // UUID for the UART BTLE client characteristic which is necessary for notifications
    public static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothDevice mConnectedDevice = null;
    private BluetoothGatt mBluetoothGatt = null;
    private BluetoothGattService mUartService = null;

    private Handler mHandler;

    @Override
    protected void onStart() {
        super.onStart();
        mConnectedDevice = ConnectedDevice.getInstance();
        if (mConnectedDevice != null) {
            mDeviceView.setText(mConnectedDevice.toString());
            connect();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.i("onStop", "-");
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
            mBluetoothGatt.close();
            Log.i("onStop", "gatt closed");
        }
        ConnectedDevice.removeInstance();
        mConnectedDevice = null;
    }

    private void connect() {
        if (mConnectedDevice != null) {
            // register call backs for bluetooth gatt
            mBluetoothGatt = mConnectedDevice.connectGatt(this, false, mBtGattCallback);
            Log.i("connect", "connectGatt called");
        }
    }

    /**
     * Callbacks for bluetooth gatt changes/updates
     * The documentation is not clear, but (some of?) the callback methods seems to
     * be executed on a worker thread - hence use a Handler when updating the ui.
     */
    private BluetoothGattCallback mBtGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                mBluetoothGatt = gatt;
                gatt.discoverServices();
                mHandler.post(new Runnable() {
                    public void run() {
                        mDataView.setText("Connected");
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // close connection and display info in ui
                mBluetoothGatt = null;
                mHandler.post(new Runnable() {
                    public void run() {
                        mDataView.setText("Disconnected");
                    }
                });
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                // debug, list services
                List<BluetoothGattService> services = gatt.getServices();
                for (BluetoothGattService service : services) {
                    String uuid = service.getUuid().toString();
                    Log.i("service", uuid);
                }

                /*
                 * Get the UART service
                 */
                mUartService = gatt.getService(UARTSERVICE_SERVICE_UUID);
                // debug, list characteristics for detected service
                if (mUartService != null) {
                    List<BluetoothGattCharacteristic> characteristics =
                            mUartService.getCharacteristics();
                    for (BluetoothGattCharacteristic chara : characteristics) {
                        Log.i("characteristic", chara.getUuid().toString());
                    }

                    /*
                     * Enable notifications on UART data
                     * First: call setCharacteristicNotification
                     */
                    BluetoothGattCharacteristic rx =
                            mUartService.getCharacteristic(UART_RX_CHARACTERISTIC_UUID);
                    boolean success = gatt.setCharacteristicNotification(rx, true);
                    if (success) {
                        gatt.readCharacteristic(rx);
                        Log.i("setCharactNotification", "success");
                        /*
                         * Second: set enable notification
                         * (why isn't this done by setCharacteristicNotification - a flaw in the API?)
                         */
                        BluetoothGattDescriptor descriptor =
                                rx.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                        gatt.writeDescriptor(descriptor); // callback: onDescriptorWrite
                    } else {
                        Log.i("setCharactNotification", "failed");
                    }
                } else {
                    mHandler.post(new Runnable() {
                        public void run() {
                            showToast("UART RX characteristic not found");
                        }
                    });
                }
            }
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, BluetoothGattDescriptor
                descriptor, int status) {
            Log.i("onDescriptorWrite", "descriptor " + descriptor.getUuid());
            Log.i("onDescriptorWrite", "status " + status);

            if (CLIENT_CHARACTERISTIC_CONFIG.equals(descriptor.getUuid()) &&
                    status == BluetoothGatt.GATT_SUCCESS) {
                mHandler.post(new Runnable() {
                    public void run() {
                        showToast("UART-data notifications enabled");
                        mDeviceView.setText("UART RX service");
                    }
                });
            }
        }

        /**
         * Call back called on characteristic changes, e.g. when a data value is changed.
         * This is where we receive notifications on updates of accelerometer data.
         */
        int[] array = new int[4];
        int i,oldAvg,average,bpmTemp;
        long startTime,showtime,bpm;
        boolean rising;
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic) {
            // TODO: check which service and characteristic caused this call (in this simple
            // example we assume it's the UART service)
            Log.i("onCharacteristicChanged", characteristic.getUuid().toString());

            // Read the data
            BluetoothGattCharacteristic rx =
                    mUartService.getCharacteristic(UART_RX_CHARACTERISTIC_UUID);
            String data = rx.getStringValue(0);
            Log.i("data as string", data);
            final int raw = Integer.parseInt(data); // refer to Micro:bit docs
            array[i++] = raw;
            if (i == 4) {
                i = 0;
                oldAvg = average;
                average = 0;
                for (int index = 0; index < array.length ; index++) {
                    average += array[index];
                }
                average = average / 8;
            }
            if (rising == false) {
                if (oldAvg < average) {
                    rising = true;
                }
            } else if (rising == true) {
                if (oldAvg > average) {
                    bpmTemp += 1;
                    rising = false;
                }
            }
            if (System.currentTimeMillis() > showtime + 500) {
                bpm = bpmTemp * 60000 / (System.currentTimeMillis() - startTime);
                showtime = System.currentTimeMillis();
                mHandler.post(new Runnable() {
                    public void run() {
                        mDataView.setText("bpm: "+(int)bpm);
                    }
                });
            }
            if (System.currentTimeMillis() > startTime + 5000) {
                bpmTemp = 0;
                startTime = System.currentTimeMillis();
            }
            Log.i("data as string", "bpmTemp: "+bpmTemp+" testBpmTemp: "+bpmTemp * 60000 / (System.currentTimeMillis() - startTime));
            // update ui

        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic, int status) {
            Log.i("onCharacteristicWrite", characteristic.getUuid().toString());
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic, int status) {
            Log.i("onCharacteristicRead", characteristic.getUuid().toString());
        }
    };

    // Below: gui stuff...
    private TextView mDeviceView;
    private TextView mDataView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device);

        mDeviceView = findViewById(R.id.deviceView);
        mDataView = findViewById(R.id.dataView);

        mHandler = new Handler();
    }

    protected void showToast(String msg) {
        Toast toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT);
        toast.show();
    }
}
