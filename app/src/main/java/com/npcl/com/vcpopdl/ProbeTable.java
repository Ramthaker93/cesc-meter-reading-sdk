package com.npcl.com.vcpopdl;


import android.util.Pair;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public class ProbeTable {

    private final Map<Pair<Integer, Integer>, Class<? extends UsbSerialDriver>> mProbeTable =   new LinkedHashMap<Pair<Integer,Integer>, Class<? extends UsbSerialDriver>>();
    /* access modifiers changed from: 0000 */
    ProbeTable addDriver(Class<? extends UsbSerialDriver> driverClass) {
        final Method method;

        try {
            method = driverClass.getMethod("getSupportedDevices");
        } catch (SecurityException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        final Map<Integer, int[]> devices;
        try {
            devices = (Map<Integer, int[]>) method.invoke(null);
        } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        for (Map.Entry<Integer, int[]> entry : devices.entrySet()) {
            final int vendorId = entry.getKey();
            for (int productId : entry.getValue()) {
                addProduct(vendorId, productId, driverClass);
            }
        }

        return this;
    }


    public ProbeTable addProduct(int vendorId, int productId,
                                 Class<? extends UsbSerialDriver> driverClass) {
        mProbeTable.put(Pair.create(vendorId, productId), driverClass);
        return this;
    }
    public Class<? extends UsbSerialDriver> findDriver(int vendorId, int productId) {
        final Pair<Integer, Integer> pair = Pair.create(vendorId, productId);
        return mProbeTable.get(pair);
    }
}

