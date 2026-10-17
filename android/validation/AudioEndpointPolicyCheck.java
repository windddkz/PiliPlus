package com.example.piliplus.official;

import static android.media.AudioDeviceInfo.*;

public final class AudioEndpointPolicyCheck {
    public static void main(String[] args) {
        assert AudioEndpointPolicy.endpoint(new int[] {TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE})
                == 1;
        for (int type : new int[] {TYPE_WIRED_HEADSET, TYPE_WIRED_HEADPHONES, TYPE_BLUETOOTH_A2DP,
                     TYPE_BLUETOOTH_SCO, TYPE_USB_HEADSET, TYPE_HEARING_AID, TYPE_BLE_HEADSET,
                     TYPE_BLE_HEARING_AID}) {
            assert AudioEndpointPolicy.endpoint(new int[] {TYPE_BUILTIN_SPEAKER, type}) == 2;
            assert AudioEndpointPolicy.endpoint(new int[] {type}) == 2;
        }
        for (int type : new int[] {TYPE_HDMI, TYPE_HDMI_ARC, TYPE_USB_DEVICE, TYPE_USB_ACCESSORY,
                     TYPE_BLE_SPEAKER, TYPE_UNKNOWN})
            assert AudioEndpointPolicy.endpoint(new int[] {type}) == 1;
        assert AudioEndpointPolicy.endpoint(new int[] {}) == 1;
        assert AudioEndpointPolicy.endpoint(new int[] {TYPE_BLUETOOTH_A2DP, TYPE_BUILTIN_SPEAKER})
                == 2;
        assert AudioEndpointPolicy.endpoint(new int[] {TYPE_BUILTIN_SPEAKER}) == 1;
        System.out.println(
                "Speaker, old official headphone types, USB/LE headset, unknown fallback and disconnect passed");
    }
}
