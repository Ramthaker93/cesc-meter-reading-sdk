package com.npcl.com.vcpopdl;

//import tw.com.prolific.app.pl2303terminal.R;
//import tw.com.prolific.app.pl2303terminal.R;
import tw.com.prolific.driver.pl2303.PL2303Driver;

        import android.content.Context;
        import android.hardware.usb.UsbManager;
        import android.widget.Toast;

public class ReadLnG {
    PL2303Driver mSerial;
    private PL2303Driver.BaudRate mBaudrate = PL2303Driver.BaudRate.B300;
    private PL2303Driver.DataBits mDataBits = PL2303Driver.DataBits.D7;
    private PL2303Driver.Parity mParity = PL2303Driver.Parity.EVEN;
    private PL2303Driver.StopBits mStopBits = PL2303Driver.StopBits.S1;
    private PL2303Driver.FlowControl mFlowControl = PL2303Driver.FlowControl.OFF;
    private static final String ACTION_USB_PERMISSION = "tw.com.prolific.pl2303hxdgpio.USB_PERMISSION";
    private UsbManager mUsbManager;

    public String  ReadMeter(Context context) {
            String Data="";



        mSerial = new PL2303Driver(  (UsbManager) context.getSystemService("usb"), context,     ACTION_USB_PERMISSION);

        // get service

        try {

            Toast.makeText(context, "Configure USB", 0).show();
            openUsbSerial( context);
            Toast.makeText(context, "Sending Command", 0).show();
            String securityLnG = "/?!\r\n";
            writeDataToSerial(securityLnG);
            Thread.sleep(100);
            Toast.makeText(context, "Read Response", 0).show();
            Data = readDataFromSerial();
            return Data;
        }

    catch (Exception Ex)
        {
            return "";
        }
    }
    public void writeDataToSerial(String Command) {

        if (this.mSerial != null && this.mSerial.isConnected()) {
            //byte securityLnG[] = new byte[]{47, 63, 33, 13, 10};

            int res = this.mSerial.write(Command.getBytes(), Command.length());
            if (res < 0) {

            }
        }
    }

    public String readDataFromSerial() {
        String sResult="";
        byte[] rbuf = new byte[20];
        StringBuffer sbHex = new StringBuffer();
        if (this.mSerial != null && this.mSerial.isConnected()) {
            int len = this.mSerial.read(rbuf);
            if (len < 0) {
            } else if (len > 0) {
                for (int j = 0; j < len; j++) {
                    sbHex.append((char) (rbuf[j] & 255));
                }
                sResult =sbHex.toString();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            } else {
            }
        }
        return sResult;
    }


    private void openUsbSerial( Context context) {

        try {
            if (this.mSerial == null) {
                Toast.makeText(context, "Serial Port Null ", 0).show();

            } else if (this.mSerial.isConnected()) {
                Toast.makeText(context, "Serial Port Connect", 0).show();
                if (!this.mSerial.InitByBaudRate(this.mBaudrate, 300)) {
                    if (!this.mSerial.PL2303Device_IsHasPermission()) {
                        Toast.makeText(context, "cannot open, maybe no permission", Toast.LENGTH_SHORT).show();

                    }
                    if (this.mSerial.PL2303Device_IsHasPermission() && !this.mSerial.PL2303Device_IsSupportChip()) {
                        Toast.makeText(context, "cannot open, maybe this chip has no support, please use PL2303HXD / RA / EA chip.", 0).show();
                        return;
                    }
                    return;
                }


            }
        }
        catch (Exception Ex)
        {

            Toast.makeText(context, Ex.getMessage().toString(), Toast.LENGTH_SHORT).show();

        }
    }
}
