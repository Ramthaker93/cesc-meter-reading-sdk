package com.npcl.com.vcpopdl;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class UsbSerialProber {
    private final ProbeTable mProbeTable;

    public UsbSerialProber(ProbeTable probeTable) {
        mProbeTable = probeTable;
    }

    public static ProbeTable getDefaultProbeTable() {
        final ProbeTable probeTable = new ProbeTable();
        // probeTable.addDriver(CdcAcmSerialDriver.class);
         probeTable.addDriver(Cp21xxSerialDriver.class);
//        probeTable.addDriver(FtdiSerialDriver.class);
//        probeTable.addDriver(ProlificSerialDriver.class);
        //  probeTable.addDriver(Ch34xSerialDriver.class);
        return probeTable;
    }

    public static UsbSerialProber getDefaultProber() {
        return new UsbSerialProber(getDefaultProbeTable());
    }

    public List<UsbSerialDriver> findAllDrivers(UsbManager usbManager) {
        ArrayList arrayList = new ArrayList();
        for (UsbDevice probeDevice : usbManager.getDeviceList().values()) {
            UsbSerialDriver probeDevice2 = probeDevice(probeDevice);
            if (probeDevice2 != null) {
                arrayList.add(probeDevice2);
            }
        }
        return arrayList;
    }

    public UsbSerialDriver probeDevice(UsbDevice usbDevice) {
        Class findDriver = this.mProbeTable.findDriver(usbDevice.getVendorId(), usbDevice.getProductId());
        if (findDriver == null) {
            return null;
        }
        try {
            return (UsbSerialDriver) findDriver.getConstructor(new Class[]{UsbDevice.class}).newInstance(new Object[]{usbDevice});
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        } catch (IllegalArgumentException e2) {
            throw new RuntimeException(e2);
        } catch (InstantiationException e3) {
            throw new RuntimeException(e3);
        } catch (IllegalAccessException e4) {
            throw new RuntimeException(e4);
        } catch (InvocationTargetException e5) {
            throw new RuntimeException(e5);
        }
    }
}
