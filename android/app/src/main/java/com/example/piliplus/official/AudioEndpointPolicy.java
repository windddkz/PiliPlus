package com.example.piliplus.official;

import static android.media.AudioDeviceInfo.*;

/** Endpoint selection only; a device list is not an AudioTrack routing measurement. */
final class AudioEndpointPolicy {
    static int endpoint(int[] deviceTypes) {
        for (int type : deviceTypes) {
            switch (type) {
                case TYPE_WIRED_HEADSET:
                case TYPE_WIRED_HEADPHONES:
                case TYPE_BLUETOOTH_A2DP:
                case TYPE_BLUETOOTH_SCO:
                case TYPE_USB_HEADSET:
                case TYPE_HEARING_AID:
                case TYPE_BLE_HEADSET:
                case TYPE_BLE_HEARING_AID:
                    return 2;
            }
        }
        // Match the old official speaker default; generic USB/HDMI do not identify headphones.
        return 1;
    }
}
