package com.npcl.com.vcpopdl;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonObject;
import com.npcl.com.vcpopdl.api.APIClientBilling;
import com.npcl.com.vcpopdl.api.APIInterfaceBilling;

import org.json.JSONObject;
import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import retrofit2.Call;

public class DownLoadPaymentData {


    String namespace = "http://npcl.com/";
    private String url = "https://iwebapps.noidapower.com:8032/VCPWebservices.asmx";
   // private String url = "https://appsqa.noidapower.com:7051/VCPWebservices.asmx";
    String SOAP_ACTION;
    SoapObject request = null, objMessages = null;
    SoapSerializationEnvelope envelope;
    HttpTransportSE androidHttpTransport;

    DownLoadPaymentData() {
    }

    protected void SetEnvelope() {

        try {

            // Creating SOAP envelope
            envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);

            //You can comment that line if your web service is not .NET one.
            envelope.dotNet = true;

            envelope.setOutputSoapObject(request);
            androidHttpTransport = new HttpTransportSE(url, 2500000);
            androidHttpTransport.debug = true;

        } catch (Exception e) {
            System.out.println("Soap Exception---->>>" + e.toString());
        }
    }

    public String getPaymentData(String MethodName, String Billmonthyear, String VcpId, String Flag, Context context) {


        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp = new PropertyInfo();
            //weightProp.setName("NoValue");
            //weightProp.setValue(NoValue);
            //weightProp.setType(String.class);
            //request.addProperty(weightProp);

            //Adding String value to request object

            request.addProperty("Billmonthyear", "" + Billmonthyear);
            request.addProperty("VcpId", "" + VcpId);
            request.addProperty("Flag", "" + Flag);


            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();


                String status = SavePaymentDataMRO(result, VcpId, context);
                return status;

            } catch (SocketTimeoutException ex) {
                return ex.toString();

            } catch (Exception e) {
                // TODO: handle exception
                return "MRO Not Downloaded" + e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "MRO Not Downloaded" + e.toString();
        }
    }

    public String SavePaymentDataMRO(String Data,String VcpId,Context context )
    {
     try
     {
         String [] Row = Data.split("#");
         String Cols = " (ConsumerNo  ,  MeterNo  ,  PoleNo  ,  Name  ,  Co  ,  HouseNo  ,  Street  , City  ,  Billmonthyear  ,  Portion  ,  LastPmt  ,  LastPmtDate  ,  LastBillAmt  ,  TotalAmt  ,  Status  ,  Arrear  ,  MobileNo,TabId,EntryDate,UserId ) ";
         String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
         String SDate=timeStamp.toString().split("_")[0].toString();
         DatabaseHandler Obj = new DatabaseHandler(context);
         for(int i=0;i< Row.length ;i++) {
             String Vals = "";
             String Qry="";

             String[] temp = Row[i].split("~");
             String consumerNo = temp[0];
             for (int j = 0; j < temp.length; j++) {
                 Vals = Vals + "'" + temp[j].toString() + "',";
             }
             Vals = Vals + "'" + SDate + "',";
             Vals = Vals + "'" + VcpId + "'";
             Qry = "insert into PaymentData " + Cols + "values (" + Vals + ");";

             java.lang.Thread.sleep(5);
             Obj.ExecuteQry(Qry) ;


         }
            return "1";
     }
     catch ( Exception ex)
     {

         return ex.getMessage().toString();
     }
    }

    public String CheckPaymentData(String MethodName, String VcpId, Context context) {


        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp = new PropertyInfo();
            //weightProp.setName("NoValue");
            //weightProp.setValue(NoValue);
            //weightProp.setType(String.class);
            //request.addProperty(weightProp);

            //Adding String value to request object
            request.addProperty("VcpId", "" + VcpId);



            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();


                //String status = SavePaymentDataMRO(result, VcpId, context);
                return result;

            } catch (SocketTimeoutException ex) {
                return ex.toString();

            } catch (Exception e) {
                // TODO: handle exception
                return "MRO Not Downloaded" + e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "MRO Not Downloaded" + e.toString();
        }
    }
}
