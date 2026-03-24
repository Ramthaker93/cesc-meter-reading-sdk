package com.npcl.com.vcpopdl;

import android.content.Context;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;
import java.net.SocketTimeoutException;

public class mrouploadbysupervisor {

    String namespace = "http://npcl.com/";
    private String url = "https://iwebapps.noidapower.com:8032/VCPWebservices.asmx";

    String SOAP_ACTION;
    SoapObject request = null, objMessages = null;
    SoapSerializationEnvelope envelope;
    HttpTransportSE androidHttpTransport;
    mrouploadbysupervisor()
    {}
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

    public String SetMroSupervisor(String MethodName, String Ablbelnr, String Ablhinw,String Adat,String Adatsoll,String Atim,String Billmonthyear,String Equnr,String Erdat,String Gpart,String ImageName,String Kva,String Kvah,String Kvrh,String Kwh,String Latitude,String Longitude,String MobileNo,String NewMeterNo,String Portion,String ReadBy,String Serge,String Termschl,String VcpId, Context context)
    {

        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp =new PropertyInfo();
            //weightProp.setName("NoValue");
            //weightProp.setValue(NoValue);
            //weightProp.setType(String.class);
            //request.addProperty(weightProp);

            //Adding String value to request object
            request.addProperty("Ablbelnr", "" + Ablbelnr);
            request.addProperty("Ablhinw", "" + Ablhinw);
            request.addProperty("Adat", "" + Adat);
            request.addProperty("Adatsoll", "" + Adatsoll);
            request.addProperty("Atim", "" + Atim);
            request.addProperty("Billmonthyear", "" + Billmonthyear);
            request.addProperty("Equnr", "" + Equnr);
            request.addProperty("Erdat", "" + Erdat);
            request.addProperty("Gpart", "" + Gpart);
            request.addProperty("ImageName", "" + ImageName);

            request.addProperty("Kva", "" + Kva);
            request.addProperty("Kvah", "" + Kvah);
            request.addProperty("Kvrh", "" + Kvrh);
            request.addProperty("Kwh", "" + Kwh);
            request.addProperty("Latitude", "" + Latitude);
            request.addProperty("Longitude", "" + Longitude);
            request.addProperty("MobileNo", "" + MobileNo);
            request.addProperty("NewMeterNo", "" + NewMeterNo);
            request.addProperty("Portion", "" + Portion);
            request.addProperty("ReadBy", "" + ReadBy);
            request.addProperty("Serge", "" + Serge);
            request.addProperty("Termschl", "" + Termschl);
            request.addProperty("VcpId", "" + VcpId);

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
                return "MRO Not Downloaded"+ e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "MRO Not Downloaded"+ e.toString();
        }

    }

    public String SetSurvey(String MethodName, String Ablbelnr, String HouseNum1, String MobileNo, String MrDate,
                                   String Partner, String PhotoId, String NoOfShops, String PremiseType,
                                   String FloorNo, String RoomNo, String AreaSqm, String NumAc, String OperHour,
                                   String SurveyDoneBy, String UserId, Context context)
    {
        try {
            SOAP_ACTION = namespace + MethodName;

            //Adding values to request object
            request = new SoapObject(namespace, MethodName);

            //Adding Double value to request object
            PropertyInfo weightProp =new PropertyInfo();
            //weightProp.setName("NoValue");
            //weightProp.setValue(NoValue);
            //weightProp.setType(String.class);
            //request.addProperty(weightProp);

            //Adding String value to request object
            request.addProperty("Ablbelnr", "" + Ablbelnr);
            request.addProperty("HouseNum1", "" + HouseNum1);
            request.addProperty("MobileNo", "" + MobileNo);
            request.addProperty("MrDate", "" + MrDate);
            request.addProperty("Partner", "" + Partner);
            request.addProperty("PhotoId", "" + PhotoId);
            request.addProperty("NoOfShops", "" + NoOfShops);
            request.addProperty("PremiseType", "" + PremiseType);
            request.addProperty("FloorNo", "" + FloorNo);
            request.addProperty("RoomNo", "" + RoomNo);

            request.addProperty("AreaSqm", "" + AreaSqm);
            request.addProperty("NumAc", "" + NumAc);
            request.addProperty("OperHour", "" + OperHour);
            request.addProperty("SurveyDoneBy", "" + SurveyDoneBy);
            request.addProperty("UserId", "" + UserId);


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
                return "MRO Not Downloaded"+ e.toString();
            }
        } catch (Exception e) {
            // TODO: handle exception
            return "MRO Not Downloaded"+ e.toString();
        }

    }
}
