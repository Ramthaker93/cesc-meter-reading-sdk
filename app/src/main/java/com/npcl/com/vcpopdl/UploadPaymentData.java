package com.npcl.com.vcpopdl;

import android.content.Context;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;
import java.net.SocketTimeoutException;



public class UploadPaymentData {

    String namespace = "http://npcl.com/";
    private String url = "https://iwebapps.noidapower.com:8032/VCPWebservices.asmx";

    String SOAP_ACTION;
    SoapObject request = null, objMessages = null;
    SoapSerializationEnvelope envelope;
    HttpTransportSE androidHttpTransport;

    UploadPaymentData() {
    }

    protected void SetEnvelope() {

        try {

            // Creating SOAP envelope
            envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);

            //You can comment that line if your web service is not .NET one.
            envelope.dotNet = true;

            envelope.setOutputSoapObject(request);
            androidHttpTransport = new HttpTransportSE(url, 60000);
            androidHttpTransport.debug = true;

        } catch (Exception e) {
            System.out.println("Soap Exception---->>>" + e.toString());
        }
    }

    public String SetPayment(String MethodName, String AmountPaid, String AuthNo, String ChequeDate, String ChequeNo, String ConsumerNo, String ContractAc, String ContractNo, String CPResult, String CPStatus, String CreatedBy, String MobileNo, String NoOfReceipt, String PayableAmt, String PaymentDate, String PaymentMode, String ReceiptNo, String TabId, String Tid, Context context) {

        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp = new PropertyInfo();

            //Adding String value to request object
            request.addProperty("AmountPaid", "" + AmountPaid);
            request.addProperty("AuthNo", "" + AuthNo);
            request.addProperty("ChequeDate", "" + ChequeDate);
            request.addProperty("ChequeNo", "" + ChequeNo);
            request.addProperty("ConsumerNo", "" + ConsumerNo);
            request.addProperty("ContractAc", "" + ContractAc);
            request.addProperty("ContractNo", "" + ContractNo);
            request.addProperty("CPResult", "" + CPResult);
            request.addProperty("CPStatus", "" + CPStatus);
            request.addProperty("CreatedBy", "" + CreatedBy);
            request.addProperty("MobileNo", "" + MobileNo);
            request.addProperty("NoOfReceipt", "" + NoOfReceipt);
            request.addProperty("PayableAmt", "" + PayableAmt);
            request.addProperty("PaymentDate", "" + PaymentDate);
            request.addProperty("PaymentMode", "" + PaymentMode);
            request.addProperty("ReceiptNo", "" + ReceiptNo);
            request.addProperty("TabId", "" + TabId);
            request.addProperty("Tid", "" + Tid);
            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();

                return result;

            } catch (SocketTimeoutException ex) {
                return ex.toString();

            } catch (Exception e) {
                // TODO: handle exception
                return "Payment Data Not Uploaded" + e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "Payment Data Not Uploaded" + e.toString();
        }

    }


    public String QRPayment(String MethodName, String ConsumerNo, String AmountPaid) {

        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp = new PropertyInfo();

            //Adding String value to request object
            request.addProperty("ConsumerNo", "" + ConsumerNo);
            request.addProperty("billAmt", "" + AmountPaid);
            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();

                return result;

            } catch (SocketTimeoutException ex) {
                return ex.toString();

            } catch (Exception e) {
                // TODO: handle exception
                return "QR String getting Error" + e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "QR String getting Error" + e.toString();
        }
    }

    public String LastPaymentStatus(String MethodName, String ConsumerNo) {

        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp = new PropertyInfo();

            //Adding String value to request object
            request.addProperty("ConsumerNo", "" + ConsumerNo);
            SetEnvelope();

            try {

                //SOAP calling webservice
                androidHttpTransport.call(SOAP_ACTION, envelope);

                //Got Webservice response
                String result = envelope.getResponse().toString();

                return result;

            } catch (SocketTimeoutException ex) {
                return ex.toString();

            } catch (Exception e) {
                // TODO: handle exception
                return "Last Payment Data Error" + e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "Last Payment Data Error" + e.toString();
        }
    }

}
