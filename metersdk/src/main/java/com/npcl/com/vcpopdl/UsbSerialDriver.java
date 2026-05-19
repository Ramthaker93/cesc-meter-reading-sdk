package com.npcl.com.vcpopdl;

import android.hardware.usb.UsbDevice;
import java.util.List;

public interface UsbSerialDriver {
    UsbDevice getDevice();

    List<UsbSerialPort> getPorts();
}
