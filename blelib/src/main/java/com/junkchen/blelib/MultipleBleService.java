/*
 * Copyright 2015 Junk Chen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.junkchen.blelib;

import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Created by JunkChen on 2015/9/11 0009.
 */
//@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class MultipleBleService extends Service implements Constants, BleListener {
    //Debug
    private static final String TAG = MultipleBleService.class.getName();

    //Member fields
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private Map<String, BluetoothGatt> mBluetoothGattMap;
    private List<BluetoothDevice> mScanLeDeviceList;
    private boolean isScanning;
    private List<String> mConnectedAddressList;//Already connected remote device address
    //Stop scanning after 10 seconds.
    private static final long SCAN_PERIOD = 10 * 1000;
    private static final int MAX_CONNECT_NUM = 16;//Can connect remote device max number.

    private OnLeScanListener mOnLeScanListener;
    private OnConnectionStateChangeListener mOnConnectionStateChangeListener;
    private OnServicesDiscoveredListener mOnServicesDiscoveredListener;
    private OnDataAvailableListener mOnDataAvailableListener;
    private OnReadRemoteRssiListener mOnReadRemoteRssiListener;
    private OnMtuChangedListener mOnMtuChangedListener;

    private final IBinder mBinder = new LocalBinder();
    private static MultipleBleService instance = null;

    public MultipleBleService() {
        instance = this;
        Log.d(TAG, "BleService initialized.");
    }

    public static MultipleBleService getInstance() {
        if (instance == null) throw new NullPointerException("MultipleBleService is not bind.");
        return instance;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        close();
        instance = null;
        return super.onUnbind(intent);
    }

    public class LocalBinder extends Binder {
        public MultipleBleService getService() {
            return MultipleBleService.this;
        }
    }

    /**
     * Check for your device to support Ble
     *
     * @return true is support    false is not support
     */
    public boolean isSupportBle() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return If return true, the initialization is successful.
     */
    public boolean initialize() {
        //For API level 18 and above, get a reference to BluetoothAdapter through BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to initialize BluetoothAdapter.");
            return false;
        }
        return true;
    }

    /**
     * Turn on or off the local Bluetooth adapter;do not use without explicit
     * user action to turn on Bluetooth.
     *
     * @param enable if open ble
     * @return if ble is open return true
     */
    public boolean enableBluetooth(boolean enable) {
        if (enable) {
            if (!mBluetoothAdapter.isEnabled()) {
                mBluetoothAdapter.enable();
            }
            return true;
        } else {
            if (mBluetoothAdapter.isEnabled()) {
                mBluetoothAdapter.disable();
            }
            return false;
        }
    }

    /**
     * Return true if Bluetooth is currently enabled and ready for use.
     *
     * @return true if the local adapter is turned on
     */
    public boolean isEnableBluetooth() {
        return mBluetoothAdapter.isEnabled();
    }

    /**
     * Scan Ble device.
     *
     * @param enable     If true, start scan ble device.False stop scan.
     * @param scanPeriod scan ble period time
     */
    public void scanLeDevice(final boolean enable, long scanPeriod) {
        Log.i(TAG, "scanLeDevice: Build.VERSION.SDK_INT = " + Build.VERSION.SDK_INT);
        if (isScanning) return;
        if (enable) {
            //Stop scanning after a predefined scan period.
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    isScanning = false;
                    if (Build.VERSION.SDK_INT >= 21) {
                        mBluetoothAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
                    } else {
                        mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    }
                    broadcastUpdate(ACTION_SCAN_FINISHED);
                    if (mScanLeDeviceList != null) {
                        mScanLeDeviceList.clear();
                        mScanLeDeviceList = null;
                    }
//                    mBluetoothAdapter.getBluetoothLeScanner().stopScan(mLeScanCallback);
                }
            }, scanPeriod);
            if (mScanLeDeviceList == null) {
                mScanLeDeviceList = new ArrayList<>();
            }
            mScanLeDeviceList.clear();
            isScanning = true;
            if (Build.VERSION.SDK_INT >= 21) {
                mBluetoothAdapter.getBluetoothLeScanner().startScan(mScanCallback);
            } else {
                mBluetoothAdapter.startLeScan(mLeScanCallback);
            }
//            mBluetoothAdapter.getBluetoothLeScanner().startScan(mLeScanCallback);
        } else {
            isScanning = false;
            if (Build.VERSION.SDK_INT >= 21) {
                mBluetoothAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
            } else {
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
            }
            broadcastUpdate(ACTION_SCAN_FINISHED);
            if (mScanLeDeviceList != null) {
                mScanLeDeviceList.clear();
                mScanLeDeviceList = null;
            }
//            mBluetoothAdapter.getBluetoothLeScanner().stopScan(mLeScanCallback);
        }
    }

    /**
     * Scan Ble device.
     *
     * @param enable If true, start scan ble device.False stop scan.
     */
    public void scanLeDevice(boolean enable) {
        this.scanLeDevice(enable, SCAN_PERIOD);
    }

    /**
     * If Ble is scaning return true, if not return false.
     *
     * @return ble whether scanning
     */
    public boolean isScanning() {
        return isScanning;
    }

    /**
     * Get scan ble devices
     *
     * @return scan le device list
     */
    public List<BluetoothDevice> getScanLeDevice() {
        return mScanLeDeviceList;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     * @return Return true if the connection is initiated successfully. The connection result
     * is reported asynchronously through the BluetoothGattCallback#onConnectionStateChange.
     */
    public boolean connect(final String address) {
        if (isScanning) scanLeDevice(false);
        if (getConnectDevices().size() > MAX_CONNECT_NUM) return false;
        if (mConnectedAddressList == null) {
            mConnectedAddressList = new ArrayList<>();
        }
        if (mConnectedAddressList.contains(address)) {
            Log.d(TAG, "This is device already connected.");
            return true;
        }
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }
        //Previously connected device.  Try to reconnect.
        if (mBluetoothGattMap == null) {
            mBluetoothGattMap = new HashMap<>();
        }
        if (mBluetoothGattMap.get(address) != null && mConnectedAddressList.contains(address)) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGattMap.get(address).connect()) {
                return true;
            } else {
                return false;
            }
        }
        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found. Unable to connect.");
            return false;
        }
        //We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        BluetoothGatt bluetoothGatt = device.connectGatt(this, false, mGattCallback);
        if (bluetoothGatt != null) {
            mBluetoothGattMap.put(address, bluetoothGatt);
            Log.d(TAG, "Trying to create a new connection.");
            mConnectedAddressList.add(address);
            return true;
        }
        return false;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the BluetoothGattCallback#onConnectionStateChange.
     *
     * @param address disconnect address
     */
    public void disconnect(final String address) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            Log.e(TAG, "BluetoothAdapter not initialized.");
            return;
        }
        mBluetoothGattMap.get(address).disconnect();
    }

    /**
     * Discovers services offered by a remote device as well as their
     * characteristics and descriptors.
     * <p>
     * Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param address Remote device address
     * @return true, if the remote service discovery has been started
     */
    public boolean discoverServices(String address) {
        if (mBluetoothGattMap.get(address) == null) return false;
        return mBluetoothGattMap.get(address).discoverServices();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     * <p>
     * Close this Bluetooth GATT client.
     *
     * @param address You will close Gatt client's address.
     */
    public void close(String address) {
        mConnectedAddressList.remove(address);
        if (mBluetoothGattMap.get(address) != null) {
            mBluetoothGattMap.get(address).close();
            mBluetoothGattMap.remove(address);
        }
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mConnectedAddressList == null) return;
        for (String address :
                mConnectedAddressList) {
            if (mBluetoothGattMap.get(address) != null) {
                mBluetoothGattMap.get(address).close();
            }
        }
        mBluetoothGattMap.clear();
        mConnectedAddressList.clear();
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the BluetoothGattCallback#onCharacteristicRead.
     *
     * @param address        The address to read from.
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(String address, BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            Log.w(TAG, "BluetoothAdapter not initialized.");
            return;
        }
        mBluetoothGattMap.get(address).readCharacteristic(characteristic);
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}, specific service UUID
     * and characteristic UUID. The read result is reported asynchronously through the
     * {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt,
     * android.bluetooth.BluetoothGattCharacteristic, int)} callback.
     *
     * @param address            The address to read from.
     * @param serviceUUID        remote device service uuid
     * @param characteristicUUID remote device characteristic uuid
     */
    public void readCharacteristic(String address, String serviceUUID, String characteristicUUID) {
        if (mBluetoothGattMap.get(address) != null) {
            BluetoothGattService service =
                    mBluetoothGattMap.get(address).getService(UUID.fromString(serviceUUID));
            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(UUID.fromString(characteristicUUID));
            mBluetoothGattMap.get(address).readCharacteristic(characteristic);
        }
    }

    /**
     * Write data to characteristic, and send to remote bluetooth le device.
     *
     * @param address            The address to read from.
     * @param serviceUUID        remote device service uuid
     * @param characteristicUUID remote device characteristic uuid
     * @param value              Send to remote ble device data.
     * @return if write success return true
     */
    public boolean writeCharacteristic(String address, String serviceUUID,
                                       String characteristicUUID, String value) {
        BluetoothGatt bluetoothGatt = mBluetoothGattMap.get(address);
        if (bluetoothGatt != null) {
            BluetoothGattService service =
                    bluetoothGatt.getService(UUID.fromString(serviceUUID));
            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(UUID.fromString(characteristicUUID));
            characteristic.setValue(value);
            return bluetoothGatt.writeCharacteristic(characteristic);
        }
        return false;
    }

    /**
     * Write data to characteristic, and send to remote bluetooth le device.
     *
     * @param address            The address to read from.
     * @param serviceUUID        remote device service uuid
     * @param characteristicUUID remote device characteristic uuid
     * @param value              Send to remote ble device data.
     * @return if write success return true
     */
    public boolean writeCharacteristic(String address, String serviceUUID,
                                       String characteristicUUID, byte[] value) {
        BluetoothGatt bluetoothGatt = mBluetoothGattMap.get(address);
        if (bluetoothGatt != null) {
            BluetoothGattService service =
                    bluetoothGatt.getService(UUID.fromString(serviceUUID));
            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(UUID.fromString(characteristicUUID));
            characteristic.setValue(value);
            return bluetoothGatt.writeCharacteristic(characteristic);
        }
        return false;
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param address        The address.
     * @param characteristic Characteristic to act on.
     * @param enabled        If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(String address,
                                              BluetoothGattCharacteristic characteristic,
                                              boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGattMap.get(address).setCharacteristicNotification(characteristic, enabled);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                UUID.fromString(GattAttributes.DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION));
        descriptor.setValue(enabled ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE :
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        mBluetoothGattMap.get(address).writeDescriptor(descriptor);
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param address            The address to read from.
     * @param serviceUUID        remote device service uuid
     * @param characteristicUUID remote device characteristic uuid
     * @param enabled            if notify is true
     */
    public void setCharacteristicNotification(String address, String serviceUUID,
                                              String characteristicUUID, boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGattMap.get(address) == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        BluetoothGattService service =
                mBluetoothGattMap.get(address).getService(UUID.fromString(serviceUUID));
        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(UUID.fromString(characteristicUUID));

        mBluetoothGattMap.get(address).setCharacteristicNotification(characteristic, enabled);

        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                UUID.fromString(GattAttributes.DESCRIPTOR_CLIENT_CHARACTERISTIC_CONFIGURATION));
        descriptor.setValue(enabled ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE :
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
        mBluetoothGattMap.get(address).writeDescriptor(descriptor);

    }

    /**
     * Reads the value for a given descriptor from the associated remote device.
     * <p>
     * <p>Once the read operation has been completed, the
     * {@link BluetoothGattCallback#onDescriptorRead} callback is
     * triggered, signaling the result of the operation.
     * <p>
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param address    The address of remote device
     * @param descriptor Descriptor value to read from the remote device
     * @return true, if the read operation was initiated successfully
     */
    public boolean readDescriptor(String address, BluetoothGattDescriptor descriptor) {
        if (mBluetoothGattMap.get(address) == null) {
            Log.w(TAG, "BluetoothGatt is null");
            return false;
        }
        return mBluetoothGattMap.get(address).readDescriptor(descriptor);
    }

    /**
     * Reads the value for a given descriptor from the associated remote device.
     *
     * @param address            The address of remote device
     * @param serviceUUID        remote device service uuid
     * @param characteristicUUID remote device characteristic uuid
     * @param descriptorUUID     remote device descriptor uuid
     * @return true, if the read operation was initiated successfully
     */
    public boolean readDescriptor(String address, String serviceUUID, String characteristicUUID,
                                  String descriptorUUID) {
        if (mBluetoothGattMap.get(address) == null) {
            Log.w(TAG, "BluetoothGatt is null");
            return false;
        }
//        try {
        BluetoothGattService service =
                mBluetoothGattMap.get(address).getService(UUID.fromString(serviceUUID));
        BluetoothGattCharacteristic characteristic =
                service.getCharacteristic(UUID.fromString(characteristicUUID));
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(UUID.fromString(descriptorUUID));
        return mBluetoothGattMap.get(address).readDescriptor(descriptor);
//        } catch (Exception e) {
//            Log.e(TAG, "read descriptor exception", e);
//            return false;
//        }
    }

    /**
     * Read the RSSI for a connected remote device.
     * <p>
     * <p>The {@link BluetoothGattCallback#onReadRemoteRssi} callback will be
     * invoked when the RSSI value has been read.
     * <p>
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission.
     *
     * @param address The address of remote device
     * @return true, if the RSSI value has been requested successfully
     */
    public boolean readRemoteRssi(String address) {
        if (mBluetoothGattMap.get(address) == null) return false;
        return mBluetoothGattMap.get(address).readRemoteRssi();
    }

    /**
     * Request an MTU size used for a given connection.
     * <p>
     * <p>When performing a write request operation (write without response),
     * the data sent is truncated to the MTU size. This function may be used
     * to request a larger MTU size to be able to send more data at once.
     * <p>
     * <p>A {@link BluetoothGattCallback#onMtuChanged} callback will indicate
     * whether this operation was successful.
     * <p>
     * <p>Requires {@link Manifest.permission#BLUETOOTH} permission.
     *
     * @return true, if the new MTU value has been requested successfully
     */
    public boolean requestMtu(String address, int mtu) {
        if (mBluetoothGattMap.get(address) == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {//Android API level >= 21
            return mBluetoothGattMap.get(address).requestMtu(mtu);
        } else {
            return false;
        }
    }

    public List<BluetoothDevice> getConnectDevices() {
        if (mBluetoothManager == null) return null;
        return mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
    }

    /**
     * Get connected number of devices at the present.
     *
     * @return Number of devices currently connected
     */
    public int getConnectNum() {
        return getConnectDevices().size();
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @param address address
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices(String address) {
        if (mBluetoothGattMap.get(address) == null) return null;
        return mBluetoothGattMap.get(address).getServices();
    }

    /**
     * Device scan callback
     */
    private final BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
            Log.i(TAG, "device name: " + device.getName() + ", address: " + device.getAddress());
//            Log.i(TAG, "mScanLeDeviceList.contains(device): " + mScanLeDeviceList.contains(device));
            if (mScanLeDeviceList.contains(device)) return;
            mScanLeDeviceList.add(device);
            if (mOnLeScanListener != null) {
                mOnLeScanListener.onLeScan(device, rssi, scanRecord);
            }
            broadcastUpdate(ACTION_BLUETOOTH_DEVICE, device);
        }
    };

    //@SuppressLint("NewApi")
    protected ScanCallback mScanCallback;

    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mScanCallback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        if (mScanLeDeviceList.contains(result.getDevice())) return;
                        mScanLeDeviceList.add(result.getDevice());
                        if (mOnLeScanListener != null) {
                            mOnLeScanListener.onLeScan(result.getDevice(), result.getRssi(), result.getScanRecord().getBytes());
                        }
                        broadcastUpdate(ACTION_BLUETOOTH_DEVICE, result.getDevice());
                        Log.i(TAG, "onScanResult: name: " + result.getDevice().getName() +
                                ", address: " + result.getDevice().getAddress() +
                                ", rssi: " + result.getRssi() + ", scanRecord: " + result.getScanRecord());
                    }
                }
            };
        }
    }

    /**
     * Implements callback methods for GATT events that the app cares about.  For example,
     * connection change and services discovered.
     */
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (mOnConnectionStateChangeListener != null) {
                mOnConnectionStateChangeListener.onConnectionStateChange(gatt, status, newState);
            }
            String intentAction;
            String tmpAddress = gatt.getDevice().getAddress();
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                Log.i(TAG, "Disconnected from GATT server.");
                broadcastUpdate(intentAction, tmpAddress);
                close(tmpAddress);
            } else if (newState == BluetoothProfile.STATE_CONNECTING) {
                intentAction = ACTION_GATT_CONNECTING;
                Log.i(TAG, "Connecting to GATT server.");
                broadcastUpdate(intentAction, tmpAddress);
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectedAddressList.add(tmpAddress);
                intentAction = ACTION_GATT_CONNECTED;
                broadcastUpdate(intentAction, tmpAddress);
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGattMap.get(tmpAddress).discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTING) {
                mConnectedAddressList.remove(tmpAddress);
                intentAction = ACTION_GATT_DISCONNECTING;
                Log.i(TAG, "Disconnecting from GATT server.");
                broadcastUpdate(intentAction, tmpAddress);
            }
        }

        // New services discovered
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (mOnServicesDiscoveredListener != null) {
                mOnServicesDiscoveredListener.onServicesDiscovered(gatt, status);
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED, gatt.getDevice().getAddress());
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        // Result of a characteristic read operation
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic, int status) {
            if (mOnDataAvailableListener != null) {
                mOnDataAvailableListener.onCharacteristicRead(gatt, characteristic, status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            String address = gatt.getDevice().getAddress();
            for (int i = 0; i < characteristic.getValue().length; i++) {
                Log.i(TAG, "address: " + address + ",Write: " + characteristic.getValue()[i]);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (mOnDataAvailableListener != null) {
                mOnDataAvailableListener.onCharacteristicChanged(gatt, characteristic);
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            if (mOnDataAvailableListener != null) {
                mOnDataAvailableListener.onDescriptorRead(gatt, descriptor, status);
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (mOnReadRemoteRssiListener != null) {
                mOnReadRemoteRssiListener.onReadRemoteRssi(gatt, rssi, status);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (mOnMtuChangedListener != null) {
                mOnMtuChangedListener.onMtuChanged(gatt, mtu, status);
            }
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, final String address) {
        final Intent intent = new Intent(action);
        intent.putExtra("address", address);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, BluetoothDevice device) {
        final Intent intent = new Intent(action);
        intent.putExtra("name", device.getName());
        intent.putExtra("address", device.getAddress());
        sendBroadcast(intent);
    }

    public void setOnLeScanListener(OnLeScanListener l) {
        mOnLeScanListener = l;
    }

    public void setOnConnectListener(OnConnectionStateChangeListener l) {
        mOnConnectionStateChangeListener = l;
    }

    public void setOnServicesDiscoveredListener(OnServicesDiscoveredListener l) {
        mOnServicesDiscoveredListener = l;
    }

    public void setOnDataAvailableListener(OnDataAvailableListener l) {
        mOnDataAvailableListener = l;
    }

    public void serOnReadRemoteRssiListener(OnReadRemoteRssiListener l) {
        mOnReadRemoteRssiListener = l;
    }

    public void setOnMtuChangedListener(OnMtuChangedListener l) {
        mOnMtuChangedListener = l;
    }
}
