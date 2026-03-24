package com.npcl.com.vcpopdl.api;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.concurrent.TimeUnit;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class APIClientBilling {
    private static final String BASE_URL = "https://gtw1.noidapower.com:9123/";

    public static APIInterfaceBilling getClient(Context context){
        String credentials = Credentials.basic("umc_srv_usr2", "Rfc4ser@wsdl");

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.addInterceptor(logging);
        builder.addInterceptor(new AuthInterceptor(credentials));
        builder.addNetworkInterceptor(new ConnectivityInterceptor(context));
        builder.connectTimeout(60, TimeUnit.SECONDS);
        builder.readTimeout(60, TimeUnit.SECONDS);
        builder.writeTimeout(60, TimeUnit.SECONDS);
        OkHttpClient client = builder.build();

        Gson gson  = new GsonBuilder().setLenient().create();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .client(client)
                .build();

        return retrofit.create(APIInterfaceBilling.class);
    }
}
