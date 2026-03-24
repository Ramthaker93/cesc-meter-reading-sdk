package com.npcl.com.vcpopdl;

import android.content.Context;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;

import java.net.SocketTimeoutException;

public class UploadRemarksLogs {


        String namespace = "http://npcl.com/";
        private String url = "https://iwebapps.noidapower.com:8032/VCPWebservices.asmx";

        String SOAP_ACTION;
        SoapObject request = null, objMessages = null;
        SoapSerializationEnvelope envelope;
        HttpTransportSE androidHttpTransport;
    UploadRemarksLogs()
        {
        }
        protected void SetEnvelope() {

            try {

                // Creating SOAP envelope
                envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);

                //You can comment that line if your web service is not .NET one.
                envelope.dotNet = true;

                envelope.setOutputSoapObject(request);
                androidHttpTransport = new HttpTransportSE(url,60000);
                androidHttpTransport.debug = true;

            } catch (Exception e) {
                System.out.println("Soap Exception---->>>" + e.toString());
            }
        }

    public String SetDLLog(String MethodName, String  METERNO , String  ConsumerNo , String USERID , String  StartDate , String Reading_Mode , String  MrNote , String MrSubRmk, Context context)
    {

        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp =new PropertyInfo();

            //Adding String value to request object
            request.addProperty("METERNO", "" + METERNO);
            request.addProperty("ConsumerNo", "" + ConsumerNo);
            request.addProperty("USERID", "" + USERID);
            request.addProperty("StartDate", "" + StartDate);
            request.addProperty("Reading_Mode", "" + Reading_Mode);
            request.addProperty("MrNote", "" + MrNote);
            request.addProperty("MrSubRmk", "" + MrSubRmk);
            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();

                return result;

            } catch (SocketTimeoutException ex) {
                return ex.toString();

            }
            catch (Exception e) {
                // TODO: handle exception
                return "Payment Data Not Uploaded"+ e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "Payment Data Not Uploaded"+ e.toString();
        }

    }
    }

