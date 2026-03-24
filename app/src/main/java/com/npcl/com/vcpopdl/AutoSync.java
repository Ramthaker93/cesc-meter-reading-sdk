package com.npcl.com.vcpopdl;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import android.database.Cursor;
import java.net.URL;
import java.net.URLConnection;
import android.content.Context;

public class AutoSync extends Service
{
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    public int onStartCommand(Intent intent, Context c) {
       // if (isConnectedToServer("", 500) == true) {
           // UploadPayment(c);
                return 1;

    }

    public void onDestroy()
    {
        super.onDestroy();

    }

    public boolean isConnectedToServer(String url, int timeout) {
        try{
            //URL myUrl = new URL("http://www.google.co.uk");
            URL myUrl = new URL("https://iwebapps.noidapower.com:8032/VCPWebservices.asmx");
            URLConnection connection = myUrl.openConnection();
            connection.setConnectTimeout(timeout);
            connection.connect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

/*
    public void UploadPayment(Context context) {

        String Sql = "Select * from PaymentDetails where UploadFlag<>'1'";
        DatabaseHandler db = new DatabaseHandler(context);
        Cursor c = db.GetData(Sql);
        int reccount=  c.getCount();
        if ( reccount >0 ) {
            if (c.moveToFirst()) {
                do {
                    String UploadFlag = c.getString(c.getColumnIndex("UploadFlag"));
                    String ConsumerNo = c.getString(c.getColumnIndex("ConsumerNo"));
                    String ContractAc = c.getString(c.getColumnIndex("ContractAc"));
                    String ContractNo = c.getString(c.getColumnIndex("ContractNo"));
                    String PaymentMode = c.getString(c.getColumnIndex("PaymentMode"));
                    String ChequeDate = c.getString(c.getColumnIndex("ChequeDate"));
                    String PayableAmt = c.getString(c.getColumnIndex("PayableAmount"));
                    String AmountPaid = c.getString(c.getColumnIndex("AmountPaid"));
                    String PaymentDate = c.getString(c.getColumnIndex("PaymentDate"));
                    String ChequeNo = c.getString(c.getColumnIndex("ChequeNo"));
                    String TabId = c.getString(c.getColumnIndex("TabId"));
                    String CreatedBy = c.getString(c.getColumnIndex("UserId"));
                    String MobileNo = c.getString(c.getColumnIndex("MobileNO"));
                    String ReceiptNo = c.getString(c.getColumnIndex("ReceiptNo"));
                    String NoOfReceipt = c.getString(c.getColumnIndex("NoOfReceipt"));
                    String Tid = c.getString(c.getColumnIndex("TID"));
                    String CPStatus = c.getString(c.getColumnIndex("CardPStatus"));
                    String CPResult = c.getString(c.getColumnIndex("PaymentResult"));
                    String AuthNo = c.getString(c.getColumnIndex("AuthNumber"));

                    UploadPaymentData com = new UploadPaymentData();
                    String Result = com.SetPayment("UpdaloadPaymentData", AmountPaid, AuthNo, ChequeDate, ChequeNo, ConsumerNo, ContractAc, ContractNo, CPResult, CPStatus, CreatedBy.toUpperCase(), MobileNo, NoOfReceipt, PayableAmt, PaymentDate, PaymentMode, ReceiptNo, TabId, Tid, context);
                    if (Result.equals("1")) {
                        Sql = "update PaymentDetails set UploadFlag='1',TransferDateTime= strftime('%d.%m.%Y',date('now')) where ConsumerNo='" + ConsumerNo + "' and PaymentDate='" + PaymentDate + "'";
                        db.ExecuteQry(Sql);
                    }
                } while (c.moveToNext());
            }
        }

    }
*/
}
