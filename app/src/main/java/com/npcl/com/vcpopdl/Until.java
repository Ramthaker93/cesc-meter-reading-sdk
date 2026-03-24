package com.npcl.com.vcpopdl;

import java.util.Calendar;
import java.util.Date;
import java.text.ParseException;
import java.text.SimpleDateFormat;

public class Until {

    public static int getYear(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.YEAR);
    }

    public static int getMonth(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.MONTH);
    }

    public static int getDate(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.DAY_OF_MONTH);
    }

    public static int getHours(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        return cal.get(Calendar.HOUR_OF_DAY);
    }

    public static int getMinutes(Date date) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);

        return cal.get(Calendar.MINUTE);
    }

    public static Date AddDate(Date sDate,int Days)
    {
        Calendar cal = Calendar.getInstance();
        cal.setTime(sDate);
        cal.add(Calendar.DATE, Days);
       return( cal.getTime());

    }


    public static Date stringToDate(String sDate) throws ParseException {

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss");
            return sdf.parse(sDate);

    }
    public static String stringToDateOlnly(Date sDate)  {

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");

        String strDate = sdf.format(sDate);
        return strDate;

    }

    public static Date Sysdate() throws ParseException {

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss");
        String currentDateandTime = sdf.format(new Date());
        return  stringToDate(currentDateandTime);
    }
}



