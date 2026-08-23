package com.centrallite.dashboard;

import android.app.admin.DeviceAdminReceiver;

/**
 * Enables the dashboard to call DevicePolicyManager.lockNow() when ignition power disappears.
 * The user must approve this once in Android's Device Administrator screen.
 */
public class CentralDeviceAdminReceiver extends DeviceAdminReceiver {
}
