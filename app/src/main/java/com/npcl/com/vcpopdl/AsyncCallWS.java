package com.npcl.com.vcpopdl;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;
import org.ksoap2.serialization.SoapPrimitive;
import android.os.AsyncTask;

class AsyncCallWS extends AsyncTask<String, Void, String> {
     final String NAMESPACE = "http://npcl.com/"; //the namespace that you'll find in the header of your asmx webservice
     String METHOD_NAME= "HelloWorld"; //the webservice method that you want to call
     String SOAP_ACTION = NAMESPACE+METHOD_NAME;
     final String URL ="http://192.158.1.199:1850/MobileService.asmx";
     String result1;
    @Override
    protected void onPreExecute() {
       //  return null;
    }
    @Override
    protected String doInBackground(String... params){


        SoapPrimitive response = null;
        try {
            SoapObject Request = new SoapObject(NAMESPACE, METHOD_NAME);
            SoapSerializationEnvelope soapEnvelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);
            soapEnvelope.dotNet = true;
            soapEnvelope.setOutputSoapObject(Request);
            HttpTransportSE transport = new HttpTransportSE(URL);
            transport.call(SOAP_ACTION, soapEnvelope);
            response = (SoapPrimitive) soapEnvelope.getResponse();
            result1= response.toString();
        } catch (Exception ex) {
            result1 = ex.getMessage().toString();
        }
        return result1.toString();
    }
    @Override
    protected void onPostExecute(String result) {
        super.onPostExecute(result);
    }

}